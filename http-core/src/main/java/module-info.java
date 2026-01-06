module io.quarkus.http.undertow.httpcore {
    exports io.undertow.httpcore;

    requires java.security.sasl;

    requires io.netty.buffer;
    requires io.netty.common;

    requires org.jboss.logging;
}
