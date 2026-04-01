package io.undertow.vertx;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.ConnectionSSLSessionInfo;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.HttpExchangeBase;
import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.InputChannel;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.httpcore.SSLSessionInfo;
import io.undertow.httpcore.StatusCodes;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.httpcore.UndertowOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.internal.buffer.BufferInternal;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.ConnectionBase;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class VertxHttpExchange extends HttpExchangeBase implements HttpExchange, InputChannel, OutputChannel, Handler<Buffer> {

    private static final int CONTINUE_STATE_NONE = 0;
    private static final int CONTINUE_STATE_REQUIRED = 1;
    private static final int CONTINUE_STATE_SENT = 2;


    private static final Logger log = Logger.getLogger(VertxHttpExchange.class);

    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final ConnectionBase connectionBase;
    private long maxEntitySize = UndertowOptions.DEFAULT_MAX_ENTITY_SIZE;
    private long uploadSize = 0l;

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
    private final Object context;
    private boolean first = true;
    private Handler<AsyncResult<Void>> upgradeHandler;
    private final boolean upgradeRequest;
    private long readTimeout = UndertowOptions.DEFAULT_READ_TIMEOUT;

    private long requestContentLength = -1;

    private Handler<HttpServerRequest> pushHandler;
    private int continueState;
    private UndertowOptionMap optionMap = UndertowOptionMap.EMPTY;

    public VertxHttpExchange(HttpServerRequest request, BufferAllocator allocator, Executor worker, Object context) {
        this(request, allocator, worker, context, null);
    }

    public VertxHttpExchange(HttpServerRequest request, BufferAllocator allocator, Executor worker, Object context, Buffer existingBody) {
        this.request = request;
        this.response = request.response();
        this.connectionBase = (ConnectionBase) request.connection();
        this.allocator = allocator;
        this.worker = worker;
        this.context = context;
        this.input1 = existingBody;

        ChannelPipeline pipeline = connectionBase.channel().pipeline();
        final ChannelHandler websocketChannelHandler = pipeline.get("webSocketExtensionHandler");
        if (websocketChannelHandler != null) {
            pipeline.remove(websocketChannelHandler);
        }
        if (isRequestEntityBodyAllowed() && !request.isEnded()) {
            request.handler(this);
            request.exceptionHandler(new Handler<Throwable>() {
                @Override
                public void handle(Throwable event) {
                    synchronized (request.connection()) {

                        if (waitingForRead) {
                            request.connection().notify();
                            if (input1 != null) {
                                release(input1);
                                input1 = null;
                            }
                            if (inputOverflow != null) {
                                while (!inputOverflow.isEmpty()) {
                                    var buff = inputOverflow.poll();
                                    release(buff);
                                }
                            }
                        }
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
                    boolean terminate = false;
                    BiConsumer<InputChannel, Object> readCallback = null;
                    Object readContext = null;
                    synchronized (request.connection()) {
                        eof = true;
                        if (requestContentLength != -1 && uploadSize != requestContentLength) {
                            //we did not read the full request
                            readError = new IOException("Failed to read full request");
                        }
                        if (waitingForRead) {
                            request.connection().notify();
                        }
                        if (VertxHttpExchange.this.readHandler != null) {
                            readCallback = VertxHttpExchange.this.readHandler;
                            VertxHttpExchange.this.readHandler = null;
                            readContext = readHandlerContext;
                            VertxHttpExchange.this.readHandlerContext = null;
                        }
                        if (input1 == null) {
                            terminate = true;
                        }
                    }
                    if (readCallback != null) {
                        readCallback.accept(VertxHttpExchange.this, readContext);
                    }
                    if (terminate) {
                        terminateRequest();
                    }
                }
            });
            request.fetch(1);
            String cl = request.headers().get(HttpHeaders.CONTENT_LENGTH);
            if (cl != null) {
                try {
                    requestContentLength = Long.parseLong(cl);
                } catch (Exception e) {
                    response.setStatusCode(StatusCodes.BAD_REQUEST);
                    response.end();
                    throw new RuntimeException("Failed to parse content length", e);
                }
            }
        } else if (existingBody != null) {
            eof = true;
        } else {
            terminateRequest();
        }
        request.response().exceptionHandler(new Handler<Throwable>() {
            @Override
            public void handle(Throwable event) {
                synchronized (request.connection()) {
                    log.debugf(event, "IO Exception ");
                    eof = true;
                    if (waitingForRead) {
                        request.connection().notify();
                    }
                    //we are not getting any more read events either
                    if (event instanceof IOException) {
                        readError = (IOException) event;
                    } else {
                        readError = new IOException(event);
                    }

                }
                terminateResponse();
                VertxHttpExchange.this.close();
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
        if (request.headers().contains(HttpHeaderNames.UPGRADE)) {
            upgradeRequest = true;
            //we always remove the websocket handler (if it's present)
            ConnectionBase connection = (ConnectionBase) request.connection();
            ChannelHandlerContext c = connection.channelHandlerContext();
        } else {
            upgradeRequest = false;
        }
        String expect = request.getHeader(io.netty.handler.codec.http.HttpHeaderNames.EXPECT);
        if (expect != null) {
            if (expect.equalsIgnoreCase("100-continue")) {
                continueState = CONTINUE_STATE_REQUIRED;
            }
        }
    }

    public Handler<HttpServerRequest> getPushHandler() {
        return pushHandler;
    }

    public VertxHttpExchange setPushHandler(Handler<HttpServerRequest> pushHandler) {
        this.pushHandler = pushHandler;
        return this;
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

    public Object getContext() {
        return context;
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
    public boolean isHttp2() {
        return request.version() == HttpVersion.HTTP_2;
    }


    @Override
    public InputChannel getInputChannel() {
        return this;
    }

    @Override
    public InetSocketAddress getDestinationAddress() {
        SocketAddress socketAddress = request.localAddress();
        if (socketAddress == null) {
            return null;
        }
        return new InetSocketAddress(socketAddress.host(), socketAddress.port());
    }

    @Override
    public InetSocketAddress getSourceAddress() {
        SocketAddress socketAddress = request.remoteAddress();
        if (socketAddress == null) {
            return null;
        }
        return new InetSocketAddress(socketAddress.host(), socketAddress.port());
    }

    @Override
    public ByteBuf readAsync() throws IOException {
        if (continueState == CONTINUE_STATE_REQUIRED) {
            continueState = CONTINUE_STATE_SENT;
            request.response().writeContinue();
        }
        synchronized (request.connection()) {
            if (readError != null) {
                throw new IOException(readError);
            } else if (input1 != null) {
                ByteBuf ret = ((BufferInternal) input1).getByteBuf();
                if (inputOverflow != null) {
                    input1 = inputOverflow.poll();
                    if (input1 == null) {
                        if (!eof) {
                            request.fetch(1);
                        }
                    }
                } else {
                    input1 = null;
                    if (!eof) {
                        request.fetch(1);
                    }
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
        synchronized (request.connection()) {
            if (isReadable()) {
                getIoThread().execute(new Runnable() {
                    @Override
                    public void run() {
                        handler.accept(VertxHttpExchange.this, context);
                    }
                });
            } else {
                this.readHandler = (BiConsumer<InputChannel, Object>) handler;
                this.readHandlerContext = context;
            }
        }
    }

    @Override
    public int readBytesAvailable() {
        synchronized (request.connection()) {
            if (input1 != null) {
                return ((BufferInternal) input1).getByteBuf().readableBytes();
            }
        }
        return 0;
    }

    @Override
    public ByteBuf readBlocking() throws IOException {
        if (continueState == CONTINUE_STATE_REQUIRED) {
            continueState = CONTINUE_STATE_SENT;
            request.response().writeContinue();
        }
        long readStart = System.currentTimeMillis();
        synchronized (request.connection()) {

            while (input1 == null && !eof && readError == null) {
                try {
                    waitingForRead = true;
                    long toWait = readTimeout - (System.currentTimeMillis() - readStart);
                    if (toWait <= 0) {
                        throw new IOException("Read timeout");
                    }
                    request.connection().wait(toWait);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException(e.getMessage());
                } finally {
                    waitingForRead = false;
                }
            }
            if (readError != null) {
                terminateRequest();
                throw new IOException(readError);
            }
            Buffer ret = input1;
            input1 = null;
            if (inputOverflow != null) {
                input1 = inputOverflow.poll();
                if (input1 == null && !request.isEnded()) {
                    request.fetch(1);
                }
            } else if (!request.isEnded()) {
                request.fetch(1);
            }

            if (ret == null) {
                terminateRequest();
            }
            if (ret == null) {
                return null;
            } else {
                return ((BufferInternal) ret).getByteBuf();
            }
        }
    }

    @Override
    public void close() {
        synchronized (request.connection()) {
            switch (request.version()) {
                case HTTP_2:
                    request.response().reset();
                    break;
                default:
                    request.connection().close();
            }
        }
    }

    @Override
    public EventExecutor getIoThread() {
        return connectionBase.channel().eventLoop();
    }

    @Override
    public void writeBlocking0(ByteBuf data, boolean last) throws IOException {
        if (upgradeRequest && getStatusCode() != 101) {
            response.headers().add(HttpHeaderNames.CONNECTION, "close");
        }
        if (responseDone) {
            if (last && data == null) {
                return;
            }
            data.release();
            throw new IOException("Response already complete");
        }
        if (last && data == null) {
            responseDone = true;
            if (upgradeHandler == null) {
                request.response().end();
            } else {
                request.response().end().onComplete(upgradeHandler);
            }
            return;
        }
        try {
            //do all this in the same lock
            synchronized (request.connection()) {
                awaitWriteable();
                try {
                    if (last) {
                        responseDone = true;
                        if (upgradeHandler == null) {
                            request.response().end(createBuffer(data));
                        } else {
                            request.response().end(createBuffer(data)).onComplete(upgradeHandler);
                        }
                    } else {
                        request.response().write(createBuffer(data));
                    }
                } catch (Exception e) {
                    if (data != null && data.refCnt() > 0) {
                        data.release();
                    }
                    throw new IOException("Failed to write", e);
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
        if (first) {
            first = false;
            return;
        }
        while (request.response().writeQueueFull()) {
            if (request.response().closed()) {
                return;
            }
            if (!drainHandlerRegistered) {
                drainHandlerRegistered = true;
                Handler<Void> handler = new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        if (waitingForWrite) {
                            HttpConnection connection = request.connection();
                            synchronized (connection) {
                                connection.notifyAll();
                            }
                        }
                    }
                };
                request.response().drainHandler(handler);
                request.response().closeHandler(handler);
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
        if (upgradeRequest && getStatusCode() != 101) {
            response.headers().add(HttpHeaderNames.CONNECTION, "close");
        }
        if (responseDone) {
            if (data != null) {
                data.release();
            }
            if (callback != null) {
                if (last && data == null) {
                    callback.onComplete(this, context);
                } else {
                    callback.onException(this, context, new IOException("Response already complete"));
                }
            }
            return;
        }
        writeQueued = true;
        if (last && data == null) {
            responseDone = true;
            if (upgradeHandler == null) {
                request.response().end();
            } else {
                request.response().end().onComplete(upgradeHandler);
            }
            queueWriteListener(callback, context, last);
            return;
        }
        if (request.response().writeQueueFull()) {
            request.response().drainHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    request.response().drainHandler(null);
                    try {
                        if (last) {
                            responseDone = true;
                            if (upgradeHandler == null) {
                                request.response().end(createBuffer(data));
                            } else {
                                request.response().end(createBuffer(data)).onComplete(upgradeHandler);
                            }
                        } else {
                            request.response().write(createBuffer(data));
                        }
                        queueWriteListener(callback, context, last);
                    } catch (Exception e) {
                        if (data != null && data.refCnt() > 0) {
                            data.release();
                        }
                        if (callback != null) {
                            callback.onException(VertxHttpExchange.this, context, new IOException("Write failed", e));
                        }
                    }
                }
            });
        } else {
            try {
                if (last) {
                    responseDone = true;
                    if (upgradeHandler == null) {
                        request.response().end(createBuffer(data));
                    } else {
                        request.response().end(createBuffer(data)).onComplete(upgradeHandler);
                    }
                } else {
                    request.response().write(createBuffer(data));
                }
                queueWriteListener(callback, context, last);
            } catch (Exception e) {
                if (data != null && data.refCnt() > 0) {
                    data.release();
                }
                if (callback != null) {
                    callback.onException(VertxHttpExchange.this, context, new IOException("Write failed", e));
                }
            }
        }
    }

    private <T> void queueWriteListener(IoCallback<T> callback, T context, boolean last) {
        connectionBase.channel().eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (last) {
                    terminateResponse();
                }

                writeQueued = false;
                if (callback != null) {
                    callback.onComplete(VertxHttpExchange.this, context);
                }

            }
        });
    }

    private Buffer createBuffer(ByteBuf data) {
        return new VertxBufferImpl(data);
    }

    private void release(Buffer buffer) {
        if (buffer instanceof BufferInternal) {
            ((BufferInternal) buffer).getByteBuf().release();
        }
    }

    @Override
    public void handle(Buffer event) {
        BiConsumer<InputChannel, Object> readCallback = null;
        Object context = null;
        if (event.length() == 0) {
            release(event);
            return;
        }
        synchronized (request.connection()) {
            uploadSize += event.length();
            if (maxEntitySizeReached()) {
                if (!responseDone) {
                    eof = true;
                    responseDone = true;
                    terminateRequest();
                    response.setStatusCode(413);
                    response.putHeader("Connection", "close");
                    response.end("Request body too large");
                    VertxHttpExchange.this.close();
                    return;
                }
            }

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

    private boolean maxEntitySizeReached() {
        return maxEntitySize != UndertowOptions.DEFAULT_MAX_ENTITY_SIZE && uploadSize > maxEntitySize;
    }

    @Override
    public Executor getWorker() {
        return worker;
    }

    @Override
    public UndertowOptionMap getUndertowOptions() {
        return optionMap;
    }

    @Override
    public void setUndertowOptions(UndertowOptionMap options) {
        this.optionMap = options;
    }

    @Override
    public void sendContinue() {
        if (continueState == CONTINUE_STATE_REQUIRED) {
            continueState = CONTINUE_STATE_SENT;
            request.response().writeContinue();
        }
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
    public boolean isPushSupported() {
        return request.version() == HttpVersion.HTTP_2 && pushHandler != null;
    }

    @Override
    public void pushResource(String path, String method, Map<String, List<String>> requestHeaders) {
        if (!isPushSupported()) {
            throw new IllegalStateException("Push not supported");
        }
        MultiMap map = MultiMap.caseInsensitiveMultiMap();
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            map.add(entry.getKey().toLowerCase(Locale.ENGLISH), entry.getValue());
        }
        response.push(HttpMethod.valueOf(method), request.authority(), path, map).onComplete(new Handler<AsyncResult<HttpServerResponse>>() {
            @Override
            public void handle(AsyncResult<HttpServerResponse> event) {
                if (event.succeeded()) {
                    PushedHttpServerRequest pushed = new PushedHttpServerRequest(request, HttpMethod.valueOf(method), path, event.result(), map);
                    pushHandler.handle(pushed);
                }
            }
        });
    }

    @Override
    public boolean isIoOperationQueued() {
        return readHandler != null || writeQueued;
    }

    @Override
    public void setMaxEntitySize(long maxEntitySize) {
        this.maxEntitySize = maxEntitySize;
    }

    @Override
    public long getMaxEntitySize() {
        return this.maxEntitySize;
    }

    @Override
    public void setReadTimeout(long readTimeoutMs) {
        readTimeout = readTimeoutMs;
    }

    @Override
    public long getReadTimeout() {
        return readTimeout;
    }


    @Override
    public void setUpgradeListener(Consumer<Object> listener) {
        Http1xServerConnection connection = (Http1xServerConnection) request.connection();
        ChannelHandlerContext context = connection.channelHandlerContext();
        upgradeHandler = new Handler<AsyncResult<Void>>() {
            @Override
            public void handle(AsyncResult<Void> event) {

                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        terminateResponse();
                        context.pipeline().remove("httpDecoder");
                        context.pipeline().remove("httpEncoder");
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
        };
    }
}
