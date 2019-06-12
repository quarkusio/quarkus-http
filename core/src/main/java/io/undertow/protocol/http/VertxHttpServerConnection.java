package io.undertow.protocol.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.io.IoCallback;
import io.undertow.server.BufferAllocator;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.SSLSessionInfo;
import io.undertow.server.ServerConnection;
import io.undertow.util.UndertowOptionMap;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.impl.ConnectionBase;

public class VertxHttpServerConnection extends ServerConnection {

    final HttpServerRequest request;
    final ConnectionBase connectionBase;
    HttpServerExchange exchange;
    final BufferAllocator allocator;
    private final Executor worker;
    private boolean responseStarted;
    private boolean inHandlerChain;


    public VertxHttpServerConnection(HttpServerRequest request, BufferAllocator allocator, Executor worker) {
        this.request = request;
        connectionBase = (ConnectionBase) request.connection();
        this.allocator = allocator;
        this.worker = worker;
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
        throw new RuntimeException("NYI");
    }

    @Override
    public void writeBlocking(ByteBuf data, boolean last, HttpServerExchange exchange) throws IOException {
        handleContentLength(data, last, exchange);
        if(last && data == null) {
            request.response().end();
            return;
        }
        awaitWriteable();
        if(last) {
            request.response().end(createBuffer(data));
        } else {
            request.response().write(createBuffer(data));
        }
    }

    private void handleContentLength(ByteBuf data, boolean last, HttpServerExchange exchange) {
        if (!responseStarted) {
            responseStarted = true;
            if(last) {
                if(!exchange.responseHeaders().contains(HttpHeaders.CONTENT_LENGTH)) {
                    request.response().headers().add(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.readableBytes()));
                }
            } else {
                if(!exchange.responseHeaders().contains(HttpHeaders.CONTENT_LENGTH)) {
                    request.response().setChunked(true);
                }
            }
        }
    }

    private void awaitWriteable() throws InterruptedIOException {
        if (request.response().writeQueueFull()) {
            CountDownLatch latch = new CountDownLatch(1);
            request.response().drainHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    latch.countDown();
                }
            });
            if(request.response().writeQueueFull()) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        }
    }

    @Override
    public <T> void writeAsync(ByteBuf data, boolean last, HttpServerExchange exchange, IoCallback<T> callback, T context) {
        handleContentLength(data, last, exchange);
        if(last && data == null) {
            request.response().end();
            callback.onComplete(exchange, context);
            return;
        }
        if(request.response().writeQueueFull()) {
            request.response().drainHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    if(last) {
                        request.response().end(createBuffer(data));
                    } else {
                        request.response().write(createBuffer(data));
                    }
                    callback.onComplete(exchange, context);
                }
            });
        } else {
            if(last) {
                request.response().end(createBuffer(data));
            } else {
                request.response().write(createBuffer(data));
            }
            callback.onComplete(exchange, context);
        }
    }

    private Buffer createBuffer(ByteBuf data) {
        return new VertxBufferImpl(data);
    }

    @Override
    protected boolean isIoOperationQueued() {
        return false;
    }

    @Override
    protected <T> void scheduleIoCallback(IoCallback<T> callback, T context, HttpServerExchange exchange) {
        callback.onComplete(exchange, context);
    }

    @Override
    public boolean isContinueResponseSupported() {
        return false;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    protected void close(HttpServerExchange exchange) {
        request.response().close();
    }

    @Override
    public SocketAddress getPeerAddress() {
        return null;
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
    public <A extends SocketAddress> A getPeerAddress(Class<A> type) {
        return null;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public <A extends SocketAddress> A getLocalAddress(Class<A> type) {
        return null;
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
    public SSLSessionInfo getSslSessionInfo() {
        return null;
    }

    @Override
    public void setSslSessionInfo(SSLSessionInfo sessionInfo, HttpServerExchange exchange) {

    }

    @Override
    public void addCloseListener(CloseListener listener) {

    }

    @Override
    protected boolean isUpgradeSupported() {
        return false;
    }

    @Override
    protected boolean isConnectSupported() {
        return false;
    }

    @Override
    protected void exchangeComplete(HttpServerExchange exchange) {

    }

    @Override
    protected void readAsync(IoCallback<ByteBuf> callback, HttpServerExchange exchange) {

    }

    @Override
    protected ByteBuf readBlocking(HttpServerExchange exchange) throws IOException {
        return null;
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
        return null;
    }

    @Override
    public boolean isRequestTrailerFieldsSupported() {
        return false;
    }

    @Override
    public ChannelPromise createPromise() {
        return null;
    }

    @Override
    public void runResumeReadWrite() {

    }

    @Override
    public <T> void writeFileAsync(RandomAccessFile file, long position, long count, HttpServerExchange exchange, IoCallback<T> context, T callback) {

    }

    @Override
    public void writeFileBlocking(RandomAccessFile file, long position, long count, HttpServerExchange exchange) throws IOException {

    }

    @Override
    protected void ungetRequestBytes(ByteBuf buffer, HttpServerExchange exchange) {

    }

    @Override
    public void discardRequest(HttpServerExchange exchange) {

    }
}
