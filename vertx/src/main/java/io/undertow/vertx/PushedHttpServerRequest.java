package io.undertow.vertx;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpFrame;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.StreamPriority;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PushedHttpServerRequest extends HttpServerRequestInternal implements HttpServerRequest {
    private final HttpServerRequestInternal original;
    private final HttpMethod method;
    private final String uri;
    private final HttpServerResponse response;
    private final MultiMap headers;

    private MultiMap params;
    private String path;
    private String query;
    private String absoluteURI;

    public PushedHttpServerRequest(HttpServerRequest original, HttpMethod method, String uri, HttpServerResponse response, MultiMap headers) {
        this.original = (HttpServerRequestInternal) original;
        this.method = method;
        this.uri = uri;
        this.response = response;
        this.headers = headers;
    }

    @Override
    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public HttpServerRequest handler(Handler<Buffer> handler) {
        return this;
    }

    @Override
    public HttpServerRequest pause() {
        return this;
    }

    @Override
    public HttpServerRequest resume() {
        return this;
    }

    @Override
    public HttpServerRequest fetch(long amount) {
        return this;
    }

    @Override
    public HttpServerRequest endHandler(Handler<Void> endHandler) {
        endHandler.handle(null);
        return this;
    }

    @Override
    public HttpVersion version() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public boolean isSSL() {
        return original.isSSL();
    }

    @Override
    public String scheme() {
        return original.scheme();
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String path() {
        if (path == null) {
            path = parsePath(uri());
        }
        return path;
    }

    @Override
    public String query() {
        synchronized (original.connection()) {
            this.query = uri != null ? parseQuery(uri) : null;
            return query;
        }
    }

    @Override
    public String host() {
        return original.host();
    }

    @Override
    public long bytesRead() {
        return 0;
    }

    @Override
    public HttpServerResponse response() {
        return response;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public String getHeader(String headerName) {
        return headers.get(headerName);
    }

    @Override
    public String getHeader(CharSequence headerName) {
        return headers.get(headerName);
    }

    @Override
    public HttpServerRequest setParamsCharset(String charset) {
        return original.setParamsCharset(charset);
    }

    @Override
    public String getParamsCharset() {
        return original.getParamsCharset();
    }

    @Override
    public MultiMap params() {
        if (params == null) {
            params = params(uri());
        }
        return params;
    }

    @Override
    public String getParam(String paramName) {
        return params().get(paramName);
    }

    @Override
    public SocketAddress remoteAddress() {
        return original.remoteAddress();
    }

    @Override
    public SocketAddress localAddress() {
        return original.localAddress();
    }

    @Override
    public SSLSession sslSession() {
        return original.sslSession();
    }

    @Override
    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return original.peerCertificateChain();
    }

    @Override
    public String absoluteURI() {
        if (absoluteURI == null) {
            try {
                absoluteURI = absoluteURI(original.host(), this);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        return absoluteURI;
    }

    @Override
    public Future<Buffer> body() {
        return original.body();
    }

    @Override
    public Future<Void> end() {
        return null;
    }

    @Override
    public Future<NetSocket> toNetSocket() {
        return original.toNetSocket();
    }

    @Override
    public HttpServerRequest setExpectMultipart(boolean expect) {
        return this;
    }

    @Override
    public boolean isExpectMultipart() {
        return false;
    }

    @Override
    public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> uploadHandler) {
        return this;
    }

    @Override
    public MultiMap formAttributes() {
        return original.formAttributes();
    }

    @Override
    public String getFormAttribute(String attributeName) {
        return original.getFormAttribute(attributeName);
    }

    @Override
    public Future<ServerWebSocket> toWebSocket() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEnded() {
        return true;
    }

    @Override
    public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        return this;
    }

    @Override
    public HttpConnection connection() {
        return original.connection();
    }

    @Override
    public HttpServerRequest streamPriorityHandler(Handler<StreamPriority> handler) {
        return this;
    }

    @Override
    public Cookie getCookie(String name) {
        return original.getCookie(name);
    }

    @Override
    public int cookieCount() {
        return original.cookieCount();
    }

    @Override
    public Map<String, Cookie> cookieMap() {
        return original.cookieMap();
    }

    @Override
    public Cookie getCookie(String name, String domain, String path) {
        return original.getCookie(name, domain, path);
    }

    @Override
    public Set<Cookie> cookies(String name) {
        return original.cookies(name);
    }

    @Override
    public Set<Cookie> cookies() {
        return original.cookies();
    }

    /**
     * Extract the path out of the uri.
     */
    static String parsePath(String uri) {
        int i;
        if (uri.charAt(0) == '/') {
            i = 0;
        } else {
            i = uri.indexOf("://");
            if (i == -1) {
                i = 0;
            } else {
                i = uri.indexOf('/', i + 3);
                if (i == -1) {
                    // contains no /
                    return "/";
                }
            }
        }

        int queryStart = uri.indexOf('?', i);
        if (queryStart == -1) {
            queryStart = uri.length();
        }
        return uri.substring(i, queryStart);
    }

    /**
     * Extract the query out of a uri or returns {@code null} if no query was found.
     */
    static String parseQuery(String uri) {
        int i = uri.indexOf('?');
        if (i == -1) {
            return null;
        } else {
            return uri.substring(i + 1, uri.length());
        }
    }

    static String absoluteURI(String serverOrigin, HttpServerRequest req) throws URISyntaxException {
        String absoluteURI;
        URI uri = new URI(req.uri());
        String scheme = uri.getScheme();
        if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
            absoluteURI = uri.toString();
        } else {
            String host = req.host();
            if (host != null) {
                absoluteURI = req.scheme() + "://" + host + uri;
            } else {
                // Fall back to the server origin
                absoluteURI = serverOrigin + uri;
            }
        }
        return absoluteURI;
    }

    static MultiMap params(String uri) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(uri);
        Map<String, List<String>> prms = queryStringDecoder.parameters();
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        if (!prms.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : prms.entrySet()) {
                params.add(entry.getKey(), entry.getValue());
            }
        }
        return params;
    }

    @Override
    public String getParam(String paramName, String defaultValue) {
        return original.getParam(paramName, defaultValue);
    }

    @Override
    public HttpServerRequest bodyHandler(Handler<Buffer> bodyHandler) {
        return original.bodyHandler(bodyHandler);
    }

    @Override
    public HttpServerRequest body(Handler<AsyncResult<Buffer>> handler) {
        return original.body(handler);
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        original.end(handler);
    }

    @Override
    public void toNetSocket(Handler<AsyncResult<NetSocket>> handler) {
        original.toNetSocket(handler);
    }

    @Override
    public int streamId() {
        return original.streamId();
    }

    @Override
    public void toWebSocket(Handler<AsyncResult<ServerWebSocket>> handler) {
        original.toWebSocket(handler);
    }

    @Override
    public StreamPriority streamPriority() {
        return original.streamPriority();
    }

    @Override
    public DecoderResult decoderResult() {
        return original.decoderResult();
    }

    @Override
    public HttpServerRequest routed(String route) {
        return original.routed(route);
    }

    @Override
    public Context context() {
        return original.context();
    }

    @Override
    public Object metric() {
        return original.metric();
    }

    @Override
    public HostAndPort authority() {
        return original.authority();
    }

    @Override
    public MultiMap params(boolean semicolonIsNormalChar) {
        return original.params(semicolonIsNormalChar);
    }
}
