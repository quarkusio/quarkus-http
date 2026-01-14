module io.quarkus.http.undertow.websockets.servlet {
    exports io.undertow.websockets.servlet;

    requires io.netty.common;
    requires io.netty.transport;

    requires jakarta.servlet;
    requires jakarta.websocket;
    requires jakarta.websocket.client;

    requires io.quarkus.http.undertow;
    requires io.quarkus.http.undertow.httpcore;
    requires io.quarkus.http.undertow.servlet;
    requires io.quarkus.http.undertow.websockets;
    requires io.netty.codec.http;

    provides io.undertow.servlet.ServletExtension with
        io.undertow.websockets.servlet.Bootstrap;
}
