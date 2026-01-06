module io.quarkus.http.undertow {
    exports io.undertow;
    exports io.undertow.attribute;
    exports io.undertow.predicate;
    exports io.undertow.security.api;
    exports io.undertow.security.handlers;
    exports io.undertow.security.idm;
    exports io.undertow.security.impl to io.quarkus.http.undertow.servlet;
    exports io.undertow.server;
    exports io.undertow.server.handlers;
    exports io.undertow.server.handlers.accesslog;
    exports io.undertow.server.handlers.builder;
    exports io.undertow.server.handlers.cache;
    exports io.undertow.server.handlers.error;
    exports io.undertow.server.handlers.form;
    exports io.undertow.server.handlers.resource;
    exports io.undertow.server.session;
    exports io.undertow.util;

    requires java.compiler;
    requires java.naming;
    requires java.security.jgss;
    requires java.sql;

    requires jdk.unsupported;

    requires io.netty.buffer;
    requires io.netty.codec.http;
    requires io.netty.common;

    requires io.quarkus.http.undertow.httpcore;

    requires org.jboss.logging;
    requires static org.jboss.logging.annotations;

    uses io.undertow.attribute.ExchangeAttributeBuilder;
    uses io.undertow.httpcore.UndertowEngine;
    uses io.undertow.predicate.PredicateBuilder;
    uses io.undertow.server.handlers.builder.HandlerBuilder;

    provides io.undertow.attribute.ExchangeAttributeBuilder with 
        io.undertow.attribute.RelativePathAttribute.Builder,
        io.undertow.attribute.RemoteIPAttribute.Builder,
        io.undertow.attribute.LocalIPAttribute.Builder,
        io.undertow.attribute.RequestProtocolAttribute.Builder,
        io.undertow.attribute.LocalPortAttribute.Builder,
        io.undertow.attribute.IdentUsernameAttribute.Builder,
        io.undertow.attribute.RequestMethodAttribute.Builder,
        io.undertow.attribute.QueryStringAttribute.Builder,
        io.undertow.attribute.RequestLineAttribute.Builder,
        io.undertow.attribute.BytesSentAttribute.Builder,
        io.undertow.attribute.DateTimeAttribute.Builder,
        io.undertow.attribute.RemoteUserAttribute.Builder,
        io.undertow.attribute.RequestURLAttribute.Builder,
        io.undertow.attribute.ThreadNameAttribute.Builder,
        io.undertow.attribute.LocalServerNameAttribute.Builder,
        io.undertow.attribute.RequestHeaderAttribute.Builder,
        io.undertow.attribute.ResponseHeaderAttribute.Builder,
        io.undertow.attribute.CookieAttribute.Builder,
        io.undertow.attribute.ResponseCodeAttribute.Builder,
        io.undertow.attribute.PredicateContextAttribute.Builder,
        io.undertow.attribute.QueryParameterAttribute.Builder,
        io.undertow.attribute.SslClientCertAttribute.Builder,
        io.undertow.attribute.SslCipherAttribute.Builder,
        io.undertow.attribute.SslSessionIdAttribute.Builder,
        io.undertow.attribute.ResponseTimeAttribute.Builder,
        io.undertow.attribute.PathParameterAttribute.Builder,
        io.undertow.attribute.TransportProtocolAttribute.Builder,
        io.undertow.attribute.RequestSchemeAttribute.Builder,
        io.undertow.attribute.HostAndPortAttribute.Builder,
        io.undertow.attribute.AuthenticationTypeExchangeAttribute.Builder,
        io.undertow.attribute.SecureExchangeAttribute.Builder,
        io.undertow.attribute.RemoteHostAttribute.Builder,
        io.undertow.attribute.RequestPathAttribute.Builder,
        io.undertow.attribute.ResolvedPathAttribute.Builder,
        io.undertow.attribute.NullAttribute.Builder;

    provides io.undertow.predicate.PredicateBuilder with
        io.undertow.predicate.PathMatchPredicate.Builder,
        io.undertow.predicate.PathPrefixPredicate.Builder,
        io.undertow.predicate.ContainsPredicate.Builder,
        io.undertow.predicate.ExistsPredicate.Builder,
        io.undertow.predicate.RegularExpressionPredicate.Builder,
        io.undertow.predicate.PathSuffixPredicate.Builder,
        io.undertow.predicate.EqualsPredicate.Builder,
        io.undertow.predicate.PathTemplatePredicate.Builder,
        io.undertow.predicate.MethodPredicate.Builder,
        io.undertow.predicate.AuthenticationRequiredPredicate.Builder,
        io.undertow.predicate.MaxContentSizePredicate.Builder,
        io.undertow.predicate.MinContentSizePredicate.Builder,
        io.undertow.predicate.SecurePredicate.Builder,
        io.undertow.predicate.IdempotentPredicate.Builder;

    provides io.undertow.server.handlers.builder.HandlerBuilder with
        io.undertow.server.handlers.builder.RewriteHandlerBuilder,
        io.undertow.server.handlers.SetAttributeHandler.Builder,
        io.undertow.server.handlers.SetAttributeHandler.ClearBuilder,
        io.undertow.server.handlers.builder.ResponseCodeHandlerBuilder,
        io.undertow.server.handlers.DisableCacheHandler.Builder,
        io.undertow.server.handlers.ProxyPeerAddressHandler.Builder,
        io.undertow.server.handlers.RedirectHandler.Builder,
        io.undertow.server.handlers.accesslog.AccessLogHandler.Builder,
        io.undertow.server.handlers.AllowedMethodsHandler.Builder,
        io.undertow.server.handlers.BlockingHandler.Builder,
        io.undertow.server.handlers.CanonicalPathHandler.Builder,
        io.undertow.server.handlers.DisallowedMethodsHandler.Builder,
        io.undertow.server.handlers.HttpTraceHandler.Builder,
        io.undertow.server.JvmRouteHandler.Builder,
        io.undertow.server.handlers.PeerNameResolvingHandler.Builder,
        io.undertow.server.handlers.RequestDumpingHandler.Builder,
        io.undertow.server.handlers.RequestLimitingHandler.Builder,
        io.undertow.server.handlers.resource.ResourceHandler.Builder,
        io.undertow.server.handlers.SSLHeaderHandler.Builder,
        io.undertow.server.handlers.ResponseRateLimitingHandler.Builder,
        io.undertow.server.handlers.URLDecodingHandler.Builder,
        io.undertow.server.handlers.PathSeparatorHandler.Builder,
        io.undertow.server.handlers.IPAddressAccessControlHandler.Builder,
        io.undertow.server.handlers.ByteRangeHandler.Builder,
        io.undertow.server.handlers.SetHeaderHandler.Builder,
        io.undertow.predicate.PredicatesHandler.DoneHandlerBuilder,
        io.undertow.predicate.PredicatesHandler.RestartHandlerBuilder,
        io.undertow.server.handlers.StuckThreadDetectionHandler.Builder,
        io.undertow.server.handlers.AccessControlListHandler.Builder,
        io.undertow.server.handlers.JDBCLogHandler.Builder,
        io.undertow.server.handlers.LocalNameResolvingHandler.Builder,
        io.undertow.server.handlers.StoredResponseHandler.Builder,
        io.undertow.server.handlers.SecureCookieHandler.Builder,
        io.undertow.server.handlers.ForwardedHandler.Builder,
        io.undertow.server.handlers.SameSiteCookieHandler.Builder;
}
