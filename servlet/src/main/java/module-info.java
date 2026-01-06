module io.quarkus.http.undertow.servlet {
    exports io.undertow.servlet;
    exports io.undertow.servlet.api;
    exports io.undertow.servlet.attribute;
    exports io.undertow.servlet.compat.rewrite;
    exports io.undertow.servlet.core;
    exports io.undertow.servlet.handlers;
    exports io.undertow.servlet.handlers.security;
    exports io.undertow.servlet.osgi;
    exports io.undertow.servlet.predicate;
    exports io.undertow.servlet.spec;
    exports io.undertow.servlet.util;

    requires java.compiler;

    requires jdk.unsupported;

    requires io.netty.buffer;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.transport;
    requires io.quarkus.http.undertow;
    requires io.quarkus.http.undertow.httpcore;

    requires jakarta.annotation;
    requires jakarta.servlet;

    requires org.jboss.logging;
    requires static org.jboss.logging.annotations;

    requires static org.osgi.core;

    uses io.undertow.servlet.ServletExtension;

    provides io.undertow.attribute.ExchangeAttributeBuilder with
        io.undertow.servlet.attribute.ServletRequestAttribute.Builder,
        io.undertow.servlet.attribute.ServletSessionAttribute.Builder,
        io.undertow.servlet.attribute.ServletSessionIdAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestURLAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestLineAttribute.Builder,
        io.undertow.servlet.attribute.ServletRelativePathAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestedSessionIdAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestedSessionIdFromCookieAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestedSessionIdValidAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestLocaleAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestCharacterEncodingAttribute.Builder,
        io.undertow.servlet.attribute.ServletContextAttribute.Builder,
        io.undertow.servlet.attribute.ServletRequestParameterAttribute.Builder,
        io.undertow.servlet.attribute.ServletNameAttribute.Builder;

    provides io.undertow.predicate.PredicateBuilder with
        io.undertow.servlet.predicate.DispatcherTypePredicate.Builder,
        io.undertow.servlet.predicate.DirectoryPredicate.Builder,
        io.undertow.servlet.predicate.FilePredicate.Builder;
}
