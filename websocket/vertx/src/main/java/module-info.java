module io.quarkus.http.undertow.websockets.vertx {
    exports io.undertow.websockets.vertx;

    requires io.netty.codec.http;
    requires io.netty.transport;
    requires io.vertx.auth.common;
    requires io.vertx.core;
    requires io.vertx.web;

    requires jakarta.websocket;
    requires jakarta.websocket.client;

    requires io.quarkus.http.undertow.websockets;
}
