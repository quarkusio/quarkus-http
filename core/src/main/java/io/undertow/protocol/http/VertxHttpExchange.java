package io.undertow.protocol.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.UndertowLogger;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.HttpExchangeBase;
import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.InputChannel;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.httpcore.SSLSessionInfo;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.server.ConnectionSSLSessionInfo;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.net.impl.ConnectionBase;

public class VertxHttpExchange extends HttpExchangeBase implements HttpExchange, InputChannel, OutputChannel, Handler<Buffer> {

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final ConnectionBase connectionBase;


    //io
    private final BufferAllocator allocator;
    private final Executor worker;
    private boolean responseStarted;
    private boolean inHandlerChain;

    private Buffer input1;
    private Deque<Buffer> inputOverflow;
    private boolean waiting = false;

    private IoCallback<ByteBuf> readCallback;

    private IoCallback<Object> writeCallback;
    private Object writeContext;

    private boolean eof = false;

    private boolean waitingForDrain;
    private boolean drainHandlerRegistered;
    private volatile boolean writeQueued = false;


    public VertxHttpExchange(HttpServerRequest request, BufferAllocator allocator, Executor worker) {
        this.request = request;
        this.response = request.response();
        this.connectionBase = (ConnectionBase) request.connection();
        this.allocator = allocator;
        this.worker = worker;
        if (!request.isEnded()) {
            request.handler(this);
            request.endHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    IoCallback<ByteBuf> readCallback = null;
                    synchronized (request.connection()) {
                        eof = true;
                        if (waiting) {
                            request.connection().notify();
                        }
                        if (VertxHttpExchange.this.readCallback != null) {
                            readCallback = VertxHttpExchange.this.readCallback;
                            VertxHttpExchange.this.readCallback = null;
                        }
                    }
                    if (readCallback != null) {
                        readCallback.onComplete(VertxHttpExchange.this, null);
                    }
                    if (input1 == null) {
                        terminateRequest();
                    }

                }
            });
            request.fetch(1);
        } else {
            terminateRequest();
        }
        request.response().exceptionHandler(new Handler<Throwable>() {
            @Override
            public void handle(Throwable event) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(event);
                //TODO: do we need this?
                event.printStackTrace();
                eof = true;
                terminateRequest();
                terminateResponse();
                request.connection().close();
            }
        });

        request.response().endHandler(new Handler<Void>() {
            @Override
            public void handle(Void event) {
                synchronized (request.connection()) {
                    if (waiting) {
                        request.connection().notify();
                    }
                }
                terminateResponse();
            }
        });
    }

    @Override
    public BufferAllocator getBufferAllocator() {
        return allocator;
    }

    @Override
    public HttpExchange setStatusCode(int code) {
        response.setStatusCode(code);
        return this;
    }

    @Override
    public int getStatusCode() {
        return response.getStatusCode();
    }

    @Override
    public String getRequestHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public List<String> getRequestHeaders(String name) {
        return request.headers().getAll(name);
    }

    @Override
    public boolean containsRequestHeader(String name) {
        return request.headers().contains(name);
    }

    @Override
    public void removeRequestHeader(String name) {
        request.headers().remove(name);
    }

    @Override
    public void setRequestHeader(String name, String value) {
        request.headers().set(name, value);
    }

    @Override
    public Collection<String> getRequestHeaderNames() {
        return request.headers().names();
    }

    @Override
    public void addRequestHeader(String name, String value) {
        request.headers().add(name, value);
    }

    @Override
    public void clearRequestHeaders() {
        request.headers().clear();
    }

    @Override
    public List<String> getResponseHeaders(String name) {
        return response.headers().getAll(name);
    }

    @Override
    public boolean containsResponseHeader(String name) {
        return response.headers().contains(name);
    }

    @Override
    public void removeResponseHeader(String name) {
        if (responseStarted) {
            return;
        }
        response.headers().remove(name);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        if (responseStarted) {
            return;
        }
        response.headers().set(name, value);
    }

    @Override
    public Collection<String> getResponseHeaderNames() {
        return response.headers().names();
    }

    @Override
    public void addResponseHeader(String name, String value) {
        if (responseStarted) {
            return;
        }
        response.headers().add(name, value);
    }

    @Override
    public void clearResponseHeaders() {
        if (responseStarted) {
            return;
        }
        response.headers().clear();
    }

    @Override
    public String getResponseHeader(String name) {
        return response.headers().get(name);
    }

    @Override
    public String getRequestMethod() {
        return request.method().name();
    }

    @Override
    public String getRequestScheme() {
        return request.scheme();
    }

    @Override
    public String getRequestURI() {
        return request.uri();
    }

    @Override
    public String getProtocol() {
        return request.version().toString();
    }

    @Override
    public boolean isInIoThread() {
        return connectionBase.channel().eventLoop().inEventLoop();
    }

    @Override
    public OutputChannel getOutputChannel() {
        return this;
    }

    @Override
    public InputChannel getInputChannel() {
        return this;
    }

    @Override
    public InputStream getInputStream() {
        return null;
    }

    @Override
    public OutputStream getOutputStream() {
        return null;
    }

    @Override
    public boolean isOutputStreamInUse() {
        return false;
    }

    @Override
    public boolean isInputStreamInUse() {
        return false;
    }

    @Override
    public InetSocketAddress getDestinationAddress() {
        return (InetSocketAddress) connectionBase.channel().localAddress();
    }

    @Override
    public InetSocketAddress getSourceAddress() {
        return (InetSocketAddress) connectionBase.channel().remoteAddress();
    }

    @Override
    public void readAsync(IoCallback<ByteBuf> callback) {
        boolean doReadCallback = false;
        ByteBuf ret = null;
        boolean needMore = false;
        synchronized (request.connection()) {
            if (input1 != null) {
                ret = input1.getByteBuf();
                if(inputOverflow!= null) {
                    input1 = inputOverflow.poll();
                    needMore = input1 == null;
                } else {
                    needMore = true;
                    input1 = null;
                }
                doReadCallback = true;
            } else if (eof) {
                doReadCallback = true;
            } else {
                this.readCallback = callback;
            }
            if(needMore) {
                request.fetch(1);
            }
        }
        if (doReadCallback) {
            ByteBuf b = ret;
            getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    if (b == null) {
                        terminateRequest();
                    }
                    callback.onComplete(VertxHttpExchange.this, b);
                }
            });
        }
    }

    @Override
    public int readBytesAvailable() {
        return 0;
    }

    @Override
    public ByteBuf readBlocking() throws IOException {
        synchronized (request.connection()) {
            while (input1 == null && !eof) {
                try {
                    waiting = true;
                    request.connection().wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                } finally {
                    waiting = false;
                }
            }
            Buffer ret = input1;
            input1 = null;
            if(inputOverflow != null) {
                input1 = inputOverflow.poll();
                if(input1 == null) {
                    request.fetch(1);
                }
            } else {
                request.fetch(1);
            }

            if (ret == null) {
                terminateRequest();
            }
            return ret == null ? null : ret.getByteBuf();
        }
    }

    @Override
    public void close() {

    }

    @Override
    public EventExecutor getIoThread() {
        return connectionBase.channel().eventLoop();
    }

    @Override
    public void writeBlocking(ByteBuf data, boolean last) throws IOException {
        handleContentLength(data, last);
        if (last && data == null) {
            request.response().end();
            return;
        }
        try {
            //do all this in the same lock
            synchronized (request.connection()) {
                awaitWriteable();
                if (last) {
                    request.response().end(createBuffer(data));
                } else {
                    request.response().write(createBuffer(data));
                }
            }
        } finally {
            if (last) {
                terminateResponse();
            }
        }
    }

    private void handleContentLength(ByteBuf data, boolean last) {
        if (!responseStarted) {
            responseStarted = true;
            if (last) {
                if (data == null) {
                    if (!containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        request.response().headers().add(HttpHeaderNames.CONTENT_LENGTH, "0");
                    }
                } else {
                    if (containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        request.response().headers().add(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(data.readableBytes()));
                    }
                }
            } else {
                if (!containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                    request.response().setChunked(true);
                }
            }
        }
    }

    private void awaitWriteable() throws InterruptedIOException {
        assert Thread.holdsLock(request.connection());
        while (request.response().writeQueueFull()) {
            if (!drainHandlerRegistered) {
                drainHandlerRegistered = true;
                request.response().drainHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        if (waitingForDrain) {
                            request.connection().notifyAll();
                        }
                    }
                });
            }
            try {
                waitingForDrain = true;
                request.connection().wait();
            } catch (InterruptedException e) {
                throw new InterruptedIOException(e.getMessage());
            } finally {
                waitingForDrain = false;
            }
        }
    }

    @Override
    public <T> void writeAsync(ByteBuf data, boolean last, IoCallback<T> callback, T context) {
        writeQueued = true;
        handleContentLength(data, last);
        if (last && data == null) {
            request.response().end();
            queueWriteListener(callback, context, last);
            return;
        }
        if (request.response().writeQueueFull()) {
            request.response().drainHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    if (last) {
                        request.response().end(createBuffer(data));
                    } else {
                        request.response().write(createBuffer(data));
                    }
                    queueWriteListener(callback, context, last);
                    request.response().drainHandler(null);
                }
            });
        } else {
            if (last) {
                request.response().end(createBuffer(data));
            } else {
                request.response().write(createBuffer(data));
            }
            queueWriteListener(callback, context, last);
        }
    }

    private <T> void queueWriteListener(IoCallback<T> callback, T context, boolean last) {
        connectionBase.channel().eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (last) {
                    terminateResponse();
                }
                callback.onComplete(VertxHttpExchange.this, context);
                writeQueued = false;
            }
        });
    }

    private Buffer createBuffer(ByteBuf data) {
        return new VertxBufferImpl(data);
    }

    @Override
    public void handle(Buffer event) {
        IoCallback<ByteBuf> readCallback = null;
        synchronized (request.connection()) {
            if (this.readCallback != null) {
                readCallback = this.readCallback;
                this.readCallback = null;
                request.fetch(1);
            } else {
                if (input1 == null) {
                    input1 = event;
                } else {
                    if(inputOverflow == null) {
                        inputOverflow = new ArrayDeque<>();
                    }
                    inputOverflow.add(event);
                }
                if (waiting) {
                    request.connection().notifyAll();
                }
            }
        }
        if (readCallback != null) {
            IoCallback<ByteBuf> f = readCallback;
            getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    f.onComplete(VertxHttpExchange.this, event.getByteBuf());
                }
            });
        }
    }

    @Override
    public Executor getWorker() {
        return worker;
    }

    @Override
    public UndertowOptionMap getUndertowOptions() {
        return UndertowOptionMap.EMPTY;
    }

    @Override
    public void sendContinue() {
        request.response().writeContinue();
    }

    @Override
    public void discardRequest() {
        request.connection().close();
    }

    @Override
    public boolean isUpgradeSupported() {
        return true;
    }

    @Override
    public SSLSessionInfo getSslSessionInfo() {
        SSLSession session = request.sslSession();
        if (session != null) {
            return new ConnectionSSLSessionInfo(session);
        }
        return null;
    }

    @Override
    public boolean isIoOperationQueued() {
        return readCallback != null || writeQueued;
    }

    @Override
    public void setMaxEntitySize(long maxEntitySize) {

    }

    @Override
    public long getMaxEntitySize() {
        return 0;
    }

    @Override
    public void setUpgradeListener(Consumer<Object> listener) {
        Http1xServerConnection connection = (Http1xServerConnection) request.connection();
        ChannelHandlerContext context = connection.channelHandlerContext();
        request.response().endHandler(new Handler<Void>() {
            @Override
            public void handle(Void event) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        terminateResponse();
                        context.pipeline().remove("httpDecoder");
                        context.pipeline().remove("httpEncoder");
                        context.pipeline().remove("websocketExtensionHandler");
                        context.pipeline().remove("handler");
                        listener.accept(context);
                    }
                };
                if (isInIoThread()) {
                    runnable.run();
                } else {
                    getIoThread().execute(runnable);
                }

            }
        });
    }
}
