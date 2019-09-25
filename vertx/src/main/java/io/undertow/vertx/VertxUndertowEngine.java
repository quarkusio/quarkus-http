package io.undertow.vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.ExchangeHandler;
import io.undertow.httpcore.UndertowEngine;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;

public class VertxUndertowEngine implements UndertowEngine {
    @Override
    public EngineInstance start(int ioThreads, Executor blockingExecutor, BufferAllocator bufferAllocator) {
        Vertx vertx = Vertx.vertx();
        return new VertxEngineInstance(vertx, blockingExecutor, ioThreads, bufferAllocator);
    }

    @Override
    public void bindHttp(EngineInstance instance, ExchangeHandler handler, int port, String host, Object options) {
        VertxEngineInstance ei = (VertxEngineInstance) instance;

        CompletableFuture<String> deploymentId = new CompletableFuture<>();
        ei.vertx.deployVerticle(new Supplier<Verticle>() {
            @Override
            public Verticle get() {
                HttpServerOptions opts = (HttpServerOptions) options;
                if (opts == null) {
                    opts = new HttpServerOptions();
                }
                return new MyVerticle(ei.allocator, port, host, ei.vertx, ei.executor, handler, opts);
            }
        }, new DeploymentOptions().setInstances(ei.ioThreads), new Handler<AsyncResult<String>>() {
            @Override
            public void handle(AsyncResult<String> event) {
                if (event.failed()) {
                    deploymentId.completeExceptionally(event.cause());
                } else {
                    deploymentId.complete(event.result());
                }
            }
        });
        try {
            deploymentId.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bindHttps(EngineInstance instance, ExchangeHandler handler, int port, String host, String keyStore, String keyStorePassword, String trustStore, String trustStorePassword, Object options) {
        HttpServerOptions opts = (HttpServerOptions) options;
        if (opts == null) {
            opts = new HttpServerOptions();
        }
        opts.setSsl(true);
        opts.setKeyStoreOptions(new JksOptions().setPath(keyStore).setPassword(keyStorePassword));
        if (trustStore != null) {
            opts.setTrustStoreOptions(new JksOptions().setPath(trustStore).setPassword(trustStorePassword));
        }
        bindHttp(instance, handler, port, host, opts);
    }

    static class VertxEngineInstance implements EngineInstance {
        final Vertx vertx;
        final Executor executor;
        final int ioThreads;
        final BufferAllocator allocator;


        VertxEngineInstance(Vertx vertx, Executor executor, int ioThreads, BufferAllocator allocator) {
            this.vertx = vertx;
            this.executor = executor;
            this.ioThreads = ioThreads;
            this.allocator = allocator;
        }

        @Override
        public void close() {
            vertx.close();
        }
    }


    private static class MyVerticle implements Verticle {


        private final BufferAllocator allocator;
        private final int port;
        private final String host;
        private HttpServer server;
        private final Vertx vertx;
        private final Executor blockingExecutor;
        private final ExchangeHandler rootHandler;
        private final HttpServerOptions options;

        public MyVerticle(BufferAllocator allocator, int port, String host, Vertx vertx, Executor blockingExecutor, ExchangeHandler rootHandler, HttpServerOptions options) {
            this.allocator = allocator;
            this.port = port;
            this.host = host;
            this.vertx = vertx;
            this.blockingExecutor = blockingExecutor;
            this.rootHandler = rootHandler;
            this.options = options;
        }

        @Override
        public Vertx getVertx() {
            return vertx;
        }

        @Override
        public void init(Vertx vertx, Context context) {

        }

        @Override
        public void start(Promise<Void> promise) throws Exception {

            server = vertx.createHttpServer(options);

            server.requestHandler(request -> {
                VertxHttpExchange delegate = new VertxHttpExchange(request, allocator, blockingExecutor, null);
                rootHandler.handle(delegate);
            });

            server.listen(port, host, new Handler<AsyncResult<HttpServer>>() {
                @Override
                public void handle(AsyncResult<HttpServer> event) {
                    if(event.failed()) {
                        promise.fail(event.cause());
                    } else {
                        promise.complete();
                    }
                }
            });
        }

        @Override
        public void stop(Promise<Void> promise) throws Exception {
            server.close(new Handler<AsyncResult<Void>>() {
                @Override
                public void handle(AsyncResult<Void> event) {
                    if(event.failed()) {
                        promise.fail(event.cause());
                    } else {
                        promise.complete();
                    }
                }
            });
        }
    }
}
