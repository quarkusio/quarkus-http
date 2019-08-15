package io.undertow.vertx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import org.jboss.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.ConnectionSSLSessionInfo;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.HttpExchangeBase;
import io.undertow.httpcore.InputChannel;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.httpcore.SSLSessionInfo;
import io.undertow.httpcore.UndertowOptionMap;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.net.impl.ConnectionBase;

public class VertxHttpExchange extends HttpExchangeBase implements HttpExchange, InputChannel, OutputChannel, Handler<Buffer> {

    private static final Logger log = Logger.getLogger(VertxHttpExchange.class);

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final ConnectionBase connectionBase;


    //io
    private final BufferAllocator allocator;
    private final Executor worker;

    private Buffer input1;
    private Deque<Buffer> inputOverflow;
    private boolean waitingForRead = false;
    private BiConsumer<InputChannel, Object> readHandler;
    private Object readHandlerContext;

    private boolean eof = false;
    private boolean eofRead = false;
    private boolean responseDone = false;

    private boolean waitingForWrite;
    private boolean drainHandlerRegistered;
    private volatile boolean writeQueued = false;
    private IOException readError;


    public VertxHttpExchange(HttpServerRequest request, BufferAllocator allocator, Executor worker) {
        this.request = request;
        this.response = request.response();
        this.connectionBase = (ConnectionBase) request.connection();
        this.allocator = allocator;
        this.worker = worker;
        if (isRequestEntityBodyAllowed() && !request.isEnded()) {
            request.handler(this);
            request.exceptionHandler(new Handler<Throwable>() {
                @Override
                public void handle(Throwable event) {
                    synchronized (request.connection()) {
                        if (event instanceof IOException) {
                            readError = (IOException) event;
                        } else {
                            readError = new IOException(event);
                        }
                    }
                }
            });
            request.endHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    BiConsumer<InputChannel, Object> readCallback = null;
                    Object readContext = null;
                    synchronized (request.connection()) {
                        eof = true;
                        if (waitingForRead) {
                            request.connection().notify();
                        }
                        if (VertxHttpExchange.this.readHandler != null) {
                            readCallback = VertxHttpExchange.this.readHandler;
                            VertxHttpExchange.this.readHandler = null;
                            readContext = readHandlerContext;
                            VertxHttpExchange.this.readHandlerContext = null;
                        }
                    }
                    if (readCallback != null) {
                        readCallback.accept(VertxHttpExchange.this, readContext);
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
                log.debugf(event, "IO Exception ");
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
                    if (waitingForWrite || waitingForRead) {
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
        if (isResponseStarted()) {
            return;
        }
        response.headers().remove(name);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        if (isResponseStarted()) {
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
        if (isResponseStarted()) {
            return;
        }
        response.headers().add(name, value);
    }

    @Override
    public void clearResponseHeaders() {
        if (isResponseStarted()) {
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
        switch (request.version()) {
            case HTTP_1_0:
                return "HTTP/1.0";
            case HTTP_1_1:
                return "HTTP/1.1";
            case HTTP_2:
                return "HTTP/2.0";
        }
        return request.version().toString();
    }

    @Override
    public boolean isInIoThread() {
        return connectionBase.channel().eventLoop().inEventLoop();
    }


    @Override
    public InputChannel getInputChannel() {
        return this;
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
    public ByteBuf readAsync() throws IOException {
        synchronized (request.connection()) {
            if (readError != null) {
                throw new IOException(readError);
            } else if (input1 != null) {
                ByteBuf ret = input1.getByteBuf();
                if (inputOverflow != null) {
                    input1 = inputOverflow.poll();
                    if (input1 == null) {
                        request.fetch(1);
                    }
                } else {
                    input1 = null;
                    request.fetch(1);
                }
                return ret;
            } else if (eof) {
                eofRead = true;
                return null;
            } else {
                throw new IllegalStateException("readAsync called when isReadable is false");
            }
        }
    }

    @Override
    public boolean isReadable() {
        synchronized (request.connection()) {
            if (eofRead) {
                return false;
            }
            return input1 != null || eof || readError != null;
        }
    }

    @Override
    public <T> void setReadHandler(BiConsumer<InputChannel, T> handler, T context) {
        this.readHandler = (BiConsumer<InputChannel, Object>) handler;
        this.readHandlerContext = context;
    }

    @Override
    public int readBytesAvailable() {
        if (input1 != null) {
            return input1.getByteBuf().readableBytes();
        }
        return 0;
    }

    @Override
    public ByteBuf readBlocking() throws IOException {
        synchronized (request.connection()) {
            while (input1 == null && !eof) {
                try {
                    waitingForRead = true;
                    request.connection().wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                } finally {
                    waitingForRead = false;
                }
            }
            Buffer ret = input1;
            input1 = null;
            if (inputOverflow != null) {
                input1 = inputOverflow.poll();
                if (input1 == null) {
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
        synchronized (request.connection()) {
            request.connection().close();
        }
    }

    @Override
    public EventExecutor getIoThread() {
        return connectionBase.channel().eventLoop();
    }

    @Override
    public void writeBlocking0(ByteBuf data, boolean last) throws IOException {
        if(responseDone) {
            if(last && data == null) {
                return;
            }
            throw new IOException("Response already complete");
        }
        if (last && data == null) {
            responseDone = true;
            request.response().end();
            return;
        }
        try {
            //do all this in the same lock
            synchronized (request.connection()) {
                awaitWriteable();
                if (last) {
                    responseDone = true;
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


    private void awaitWriteable() throws InterruptedIOException {
        assert Thread.holdsLock(request.connection());
        while (request.response().writeQueueFull()) {
            if (!drainHandlerRegistered) {
                drainHandlerRegistered = true;
                request.response().drainHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        if (waitingForWrite) {
                            request.connection().notifyAll();
                        }
                    }
                });
            }
            try {
                waitingForWrite = true;
                request.connection().wait();
            } catch (InterruptedException e) {
                throw new InterruptedIOException(e.getMessage());
            } finally {
                waitingForWrite = false;
            }
        }
    }

    @Override
    public <T> void writeAsync0(ByteBuf data, boolean last, IoCallback<T> callback, T context) {
        if(responseDone) {
            if(last && data == null) {
                callback.onComplete(this, context);
            } else {
                callback.onException(this, context, new IOException("Response already complete"));
            }
            return;
        }
        writeQueued = true;
        if (last && data == null) {
            responseDone = true;
            request.response().end();
            queueWriteListener(callback, context, last);
            return;
        }
        if (request.response().writeQueueFull()) {
            request.response().drainHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    if (last) {
                        responseDone = true;
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
                responseDone = true;
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
        BiConsumer<InputChannel, Object> readCallback = null;
        Object context = null;
        synchronized (request.connection()) {
            if (input1 == null) {
                input1 = event;
            } else {
                if (inputOverflow == null) {
                    inputOverflow = new ArrayDeque<>();
                }
                inputOverflow.add(event);
            }
            if (waitingForRead) {
                request.connection().notifyAll();
            }
            if (readHandler != null) {
                readCallback = readHandler;
                readHandler = null;
                context = readHandlerContext;
                readHandlerContext = null;
            }
        }
        if (readCallback != null) {
            BiConsumer<InputChannel, Object> f = readCallback;
            Object c = context;
            getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    f.accept(VertxHttpExchange.this, c);
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
        if (!eof) {
            request.connection().close();
        }
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
        return readHandler != null || writeQueued;
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
