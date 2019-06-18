package io.undertow.protocol.http;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaders;
import io.undertow.server.BufferAllocator;
import io.undertow.server.Connectors;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public class VertxHttpServerInitializer implements Closeable  {

    private final ExecutorService blockingExecutor;
    private final HttpHandler rootHandler;
    private final int bufferSize;
    private final boolean directBuffers;
    private final Vertx vertx;
    private final int ioThreads;

    String deploymentId;

    public VertxHttpServerInitializer(ExecutorService blockingExecutor, HttpHandler rootHandler, int bufferSize, Vertx vertx, boolean directBuffers, int ioThreads) {
        this.blockingExecutor = blockingExecutor;
        this.rootHandler = rootHandler;
        this.bufferSize = bufferSize;
        this.directBuffers = directBuffers;
        this.vertx = vertx;
        this.ioThreads = ioThreads;
    }

    public void runServer(String host, int port) {

        BufferAllocator allocator = new BufferAllocator() {
            @Override
            public ByteBuf allocateBuffer() {
                return PooledByteBufAllocator.DEFAULT.buffer(bufferSize);
            }

            @Override
            public ByteBuf allocateBuffer(boolean direct) {
                if (direct) {
                    return PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
                } else {
                    return PooledByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
                }
            }

            @Override
            public ByteBuf allocateBuffer(int bufferSize) {
                return PooledByteBufAllocator.DEFAULT.buffer(bufferSize);
            }

            @Override
            public ByteBuf allocateBuffer(boolean direct, int bufferSize) {
                if (direct) {
                    return PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
                } else {
                    return PooledByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
                }
            }
        };
        CountDownLatch latch = new CountDownLatch(1);
        vertx.deployVerticle(new Supplier<Verticle>() {
            @Override
            public Verticle get() {
                return new MyVerticle(allocator, port, host, vertx, blockingExecutor, rootHandler);
            }
        }, new DeploymentOptions().setInstances(ioThreads), new Handler<AsyncResult<String>>() {
            @Override
            public void handle(AsyncResult<String> event) {
                deploymentId = event.result();
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void close() {
       if(deploymentId != null) {
           vertx.undeploy(deploymentId);
       }
    }

    private static class MyVerticle implements Verticle {


        private final BufferAllocator allocator;
        private final int port;
        private final String host;
        private HttpServer server;
        private final Vertx vertx;
        private final Executor blockingExecutor;
        private final HttpHandler rootHandler;

        public MyVerticle(BufferAllocator allocator, int port, String host, Vertx vertx, Executor blockingExecutor, HttpHandler rootHandler) {
            this.allocator = allocator;
            this.port = port;
            this.host = host;
            this.vertx = vertx;
            this.blockingExecutor = blockingExecutor;
            this.rootHandler = rootHandler;
        }

        @Override
        public Vertx getVertx() {
            return vertx;
        }

        @Override
        public void init(Vertx vertx, Context context) {

        }

        @Override
        public void start(Future<Void> startFuture) throws Exception {

            server = vertx.createHttpServer();

            server.requestHandler(request -> {

                VertxHttpServerConnection con = new VertxHttpServerConnection(request, allocator, blockingExecutor);

                HttpServerExchange exchange = new HttpServerExchange(con, (HttpHeaders) request.headers(), (HttpHeaders) request.response().headers(), -1);
                Connectors.setExchangeRequestPath(exchange, request.uri(), "UTF-8", true, false, new StringBuilder());
                exchange.requestMethod(request.rawMethod());
                exchange.setRequestScheme("http");
                exchange.protocol("HTTP/1.1");

                con.exchange = exchange;
                if (request.isEnded()) {
                    Connectors.terminateRequest(exchange);
                }
                Connectors.executeRootHandler(rootHandler, exchange);
            });

            server.listen(port, host);
            startFuture.complete();
        }

        @Override
        public void stop(Future<Void> stopFuture) throws Exception {
            server.close();
            stopFuture.complete();
        }
    }
}
