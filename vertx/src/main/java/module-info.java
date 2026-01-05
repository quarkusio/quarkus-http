module io.quarkus.http.undertow.vertx {
    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.transport;
    requires io.vertx.core;

    requires io.quarkus.http.undertow.httpcore;

    requires org.jboss.logging;

    provides io.undertow.httpcore.UndertowEngine with
        io.undertow.vertx.VertxUndertowEngine;
}
