module io.quarkus.http.undertow.websockets {
    exports io.undertow.websockets;
    exports io.undertow.websockets.annotated;
    exports io.undertow.websockets.handshake;
    exports io.undertow.websockets.util;

    requires java.compiler;

    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.transport;

    requires jakarta.websocket;
    requires jakarta.websocket.client;

    requires org.jboss.logging;
    requires static org.jboss.logging.annotations;

    uses io.undertow.websockets.WebsocketClientSslProvider;

    provides io.undertow.websockets.WebsocketClientSslProvider with
        io.undertow.websockets.DefaultWebSocketClientSslProvider;

    provides jakarta.websocket.ContainerProvider with
        io.undertow.websockets.UndertowContainerProvider;

    provides jakarta.websocket.server.ServerEndpointConfig.Configurator with
        io.undertow.websockets.DefaultContainerConfigurator;
}
