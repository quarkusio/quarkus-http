package io.undertow.protocol.http;

import java.util.concurrent.ExecutorService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaders;
import io.undertow.server.BufferAllocator;
import io.undertow.server.Connectors;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public class VertxHttpServerInitializer {

    private final ExecutorService blockingExecutor;
    private final HttpHandler rootHandler;
    private final int bufferSize;
    private final boolean directBuffers;

    public VertxHttpServerInitializer(ExecutorService blockingExecutor, HttpHandler rootHandler, int bufferSize, boolean directBuffers) {
        this.blockingExecutor = blockingExecutor;
        this.rootHandler = rootHandler;
        this.bufferSize = bufferSize;
        this.directBuffers = directBuffers;
    }

    public void runServer(String host, int port) {
        Vertx vertx = Vertx.vertx();

        int bufferSize = 1024 * 8;
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

        HttpServer server = vertx.createHttpServer();

        server.requestHandler(request -> {

            VertxHttpServerConnection con = new VertxHttpServerConnection(request, allocator, blockingExecutor);
            HttpServerExchange exchange = new HttpServerExchange(con, (HttpHeaders) request.headers(), (HttpHeaders) request.response().headers(), -1);
            Connectors.setExchangeRequestPath(exchange, request.uri(), "UTF-8", true, false, new StringBuilder());
            exchange.requestMethod(request.rawMethod());
            exchange.setRequestScheme("http");

            con.exchange = exchange;
            Connectors.executeRootHandler(rootHandler, exchange);
        });

        server.listen(port, host);
    }
}
