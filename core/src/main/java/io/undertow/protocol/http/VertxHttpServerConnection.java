package io.undertow.protocol.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.UndertowLogger;
import io.undertow.httpcore.IoCallback;
import io.undertow.server.BufferAllocator;
import io.undertow.server.ConnectionSSLSessionInfo;
import io.undertow.server.Connectors;
import io.undertow.server.HttpContinue;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.SSLSessionInfo;
import io.undertow.server.ServerConnection;
import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.util.UndertowOptionMap;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.http.impl.VertxHttpRequestDecoder;
import io.vertx.core.net.impl.ConnectionBase;

public class VertxHttpServerConnection extends ServerConnection implements Handler<Buffer> {

    final HttpServerRequest request;
    final ConnectionBase connectionBase;
    HttpServerExchange exchange;
    final BufferAllocator allocator;
    private final Executor worker;
    private boolean responseStarted;
    private boolean inHandlerChain;

    private Buffer input1;
    private Buffer input2;
    private boolean waiting = false;


    private IoCallback<ByteBuf> readCallback;

    private IoCallback<Object> writeCallback;
    private Object writeContext;

    private boolean eof = false;

    private boolean waitingForDrain;
    private boolean drainHandlerRegistered;
    private volatile boolean writeQueued = false;

    public VertxHttpServerConnection(HttpServerRequest request, BufferAllocator allocator, Executor worker) {
        this.request = request;
        connectionBase = (ConnectionBase) request.connection();
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
                        if (VertxHttpServerConnection.this.readCallback != null) {
                            readCallback = VertxHttpServerConnection.this.readCallback;
                            VertxHttpServerConnection.this.readCallback = null;
                        }
                    }
                    if (readCallback != null) {
                        readCallback.onComplete(exchange, null);
                    }
                }
            });
        }
        request.response().exceptionHandler(new Handler<Throwable>() {
            @Override
            public void handle(Throwable event) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(event);
                //TODO: do we need this?
                eof = true;
                Connectors.terminateRequest(exchange);
                Connectors.terminateResponse(exchange);
                exchange.endExchange();
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
                Connectors.terminateResponse(exchange);
            }
        });
        request.resume();
    }


    @Override
    protected ByteBuf allocateBuffer() {
        return allocator.allocateBuffer();
    }

    @Override
    protected ByteBuf allocateBuffer(boolean direct) {
        return allocator.allocateBuffer(direct);
    }

    @Override
    protected ByteBuf allocateBuffer(boolean direct, int bufferSize) {
        return allocator.allocateBuffer(direct, bufferSize);
    }

    @Override
    protected ByteBuf allocateBuffer(int bufferSize) {
        return allocator.allocateBuffer(bufferSize);
    }

    @Override
    public Executor getWorker() {
        return worker;
    }

    @Override
    public EventExecutor getIoThread() {
        return connectionBase.channel().eventLoop();
    }

    @Override
    public void sendContinueIfRequired() {
        if (HttpContinue.requiresContinueResponse(exchange)) {
            request.response().writeContinue();
        }
    }

    @Override
    public void writeBlocking(ByteBuf data, boolean last, HttpServerExchange exchange) throws IOException {
        if(data != null) {
            Connectors.updateResponseBytesSent(exchange, data.writableBytes());
        }
        try {
            boolean first = !responseStarted;
            handleContentLength(data, last, exchange);
            if (last && data == null) {
                request.response().end();
                return;
            }

            //do all this in the same lock
            synchronized (request.connection()) {
                awaitWriteable();
                if (last) {
                    request.response().end(createBuffer(data));
                } else {
                    request.response().write(createBuffer(data));
                }
            }
            if(first) {
                //we make sure the data has actually be written for the first request, to make sure
                //the headers are no longer mutable
                CountDownLatch latch = new CountDownLatch(1);
                getIoThread().execute(new Runnable() {
                    @Override
                    public void run() {
                        ((ConnectionBase)request.connection()).channel().flush();
                        latch.countDown();
                    }
                });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        } finally {
            if (last) {
                Connectors.terminateResponse(exchange);
            }
        }
    }

    private void handleContentLength(ByteBuf data, boolean last, HttpServerExchange exchange) {
        if (!responseStarted) {
            responseStarted = true;
            request.response().setStatusCode(exchange.getStatusCode());
            if (last) {
                if (data == null) {
                    if (!exchange.containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        request.response().headers().add(HttpHeaderNames.CONTENT_LENGTH, "0");
                    }
                } else {
                    if (!exchange.containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        request.response().headers().add(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(data.readableBytes()));
                    }
                }
            } else {
                if (!exchange.containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
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
    public <T> void writeAsync(ByteBuf data, boolean last, HttpServerExchange exchange, IoCallback<T> callback, T context) {
        writeQueued = true;
        if(data != null) {
            Connectors.updateResponseBytesSent(exchange, data.writableBytes());
        }
        handleContentLength(data, last, exchange);
        if (last && data == null) {
            request.response().end();
            queueWriteListener(exchange, callback, context, last);
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
                    queueWriteListener(exchange, callback, context, last);
                    request.response().drainHandler(null);
                }
            });
        } else {
            if (last) {
                request.response().end(createBuffer(data));
            } else {
                request.response().write(createBuffer(data));
            }
            queueWriteListener(exchange, callback, context, last);
        }
    }

    private <T> void queueWriteListener(HttpServerExchange exchange, IoCallback<T> callback, T context, boolean last) {
        getIoThread().execute(new Runnable() {
            @Override
            public void run() {
                if (last) {
                    Connectors.terminateResponse(exchange);
                }
                callback.onComplete(exchange, context);
                writeQueued = false;
            }
        });
    }

    private Buffer createBuffer(ByteBuf data) {
        return new VertxBufferImpl(data);
    }

    @Override
    protected boolean isIoOperationQueued() {
        return readCallback != null || writeQueued;
    }

    @Override
    protected <T> void scheduleIoCallback(IoCallback<T> callback, T context, HttpServerExchange exchange) {
        getIoThread().execute(new Runnable() {
            @Override
            public void run() {
                callback.onComplete(exchange, context);
            }
        });
    }

    @Override
    public boolean isContinueResponseSupported() {
        return true;
    }

    @Override
    public boolean isOpen() {
        return !request.response().closed();
    }

    @Override
    protected void close(HttpServerExchange exchange) {
        request.response().close();
    }

    @Override
    public InetSocketAddress getPeerAddress() {
        return (InetSocketAddress) ((ConnectionBase) request.connection()).channel().remoteAddress();
    }

    @Override
    protected boolean isExecutingHandlerChain() {
        return inHandlerChain;
    }

    @Override
    protected void beginExecutingHandlerChain(HttpServerExchange exchange) {
        inHandlerChain = true;
    }

    @Override
    protected void endExecutingHandlerChain(HttpServerExchange exchange) {
        inHandlerChain = false;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) ((ConnectionBase) request.connection()).channel().localAddress();
    }

    @Override
    public UndertowOptionMap getUndertowOptions() {
        return UndertowOptionMap.EMPTY;
    }

    @Override
    public int getBufferSize() {
        return 8000;
    }

    @Override
    protected SSLSessionInfo getSslSessionInfo() {
        if (request.sslSession() != null) {
            return new ConnectionSSLSessionInfo(request.sslSession());
        }
        return null;
    }

    protected void setUpgradeListener(Consumer<Object> listener) {
        Http1xServerConnection connection = (Http1xServerConnection) request.connection();
        ChannelHandlerContext context = connection.channelHandlerContext();
        request.response().endHandler(new Handler<Void>() {
            @Override
            public void handle(Void event) {
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        Connectors.terminateResponse(exchange);
                        context.pipeline().remove("httpDecoder");
                        context.pipeline().remove("httpEncoder");
                        context.pipeline().remove("websocketExtensionHandler");
                        context.pipeline().remove("handler");
                        System.out.println(context.pipeline());
                        listener.accept(context);
                    }
                };
                if(exchange.isInIoThread()) {
                    runnable.run();
                } else {
                    getIoThread().execute(runnable);
                }

            }
        });
    }

    @Override
    protected boolean isUpgradeSupported() {
        return request.connection() instanceof Http1xServerConnection;
    }

    @Override
    protected boolean isConnectSupported() {
        return false;
    }

    @Override
    protected void readAsync(IoCallback<ByteBuf> callback, HttpServerExchange exchange) {
        boolean doReadCallback = false;
        ByteBuf ret = null;
        boolean resume = false;
        synchronized (request.connection()) {
            resume = input2 != null;
            if (input1 != null) {
                ret = input1.getByteBuf();
                input1 = input2;
                input2 = null;
                doReadCallback = true;
            } else if (eof) {
                doReadCallback = true;
            } else {
                this.readCallback = callback;
            }
        }
        if (doReadCallback) {
            ByteBuf b = ret;
            boolean res = resume;
            getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    if (b == null) {
                        Connectors.terminateRequest(exchange);
                    }
                    callback.onComplete(exchange, b);
                    if (res) {
                        request.resume();
                    }
                }
            });
        }
    }

    @Override
    protected ByteBuf readBlocking(HttpServerExchange exchange) throws IOException {
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
            input1 = input2;
            if (input2 != null) {
                input2 = null;
                request.resume();
            }
            if (ret == null) {
                Connectors.terminateRequest(exchange);
            }
            return ret == null ? null : ret.getByteBuf();
        }
    }

    @Override
    protected int readBytesAvailable(HttpServerExchange exchange) {
        return 0;
    }

    @Override
    protected void maxEntitySizeUpdated(HttpServerExchange exchange) {

    }

    @Override
    public String getTransportProtocol() {
        return request.version().toString();
    }

    @Override
    public boolean isRequestTrailerFieldsSupported() {
        return false;
    }

    @Override
    public void runResumeReadWrite() {

    }

    @Override
    public <T> void writeFileAsync(RandomAccessFile file, long position, long count, HttpServerExchange exchange, IoCallback<T> context, T callback) {
        request.connection().close();
        exchange.endExchange();
        context.onException(exchange, callback, new IOException("NYI"));
    }

    @Override
    public void writeFileBlocking(RandomAccessFile file, long position, long count, HttpServerExchange exchange) throws IOException {
        request.connection().close();
        exchange.endExchange();
        throw new IOException("NYI");
    }

    @Override
    protected void ungetRequestBytes(ByteBuf buffer, HttpServerExchange exchange) {
        throw new RuntimeException("NYI");
    }

    @Override
    public void discardRequest(HttpServerExchange exchange) {

    }

    @Override
    public void handle(Buffer event) {
        IoCallback<ByteBuf> readCallback = null;
        synchronized (request.connection()) {
            if (input2 != null) {
                new IOException().printStackTrace();
            }
            if (this.readCallback != null) {
                readCallback = this.readCallback;
                this.readCallback = null;
            } else {
                if (input1 == null) {
                    input1 = event;
                } else {
                    input2 = event;
                    request.pause();
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
                    f.onComplete(exchange, event.getByteBuf());
                }
            });
        }
    }
}
