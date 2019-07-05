/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server;

import static io.undertow.util.Bits.allAreClear;
import static io.undertow.util.Bits.allAreSet;
import static io.undertow.util.Bits.anyAreClear;
import static io.undertow.util.Bits.anyAreSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.CompletedListener;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.HttpMethodNames;
import io.undertow.httpcore.HttpProtocolNames;
import io.undertow.httpcore.InputChannel;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.httpcore.SSLSessionInfo;
import io.undertow.httpcore.StatusCodes;
import io.undertow.security.api.SecurityContext;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Cookies;
import io.undertow.util.IoUtils;
import io.undertow.util.NetworkUtils;
import io.undertow.util.Rfc6265CookieSupport;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.httpcore.UndertowOptions;

/**
 * An HTTP server request/response exchange.  An instance of this class is constructed as soon as the request headers are
 * fully parsed.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class HttpServerExchange extends AbstractAttachable implements BufferAllocator, OutputChannel, InputChannel, CompletedListener {

    // immutable state
    private static final String HTTPS = "https";

    /**
     * Attachment key that can be used to hold additional request attributes
     */
    public static final AttachmentKey<Map<String, String>> REQUEST_ATTRIBUTES = AttachmentKey.create(Map.class);

    /**
     * Attachment key that can be used as a flag of secure attribute
     */
    public static final AttachmentKey<Boolean> SECURE_REQUEST = AttachmentKey.create(Boolean.class);

    private static final BiConsumer<InputChannel, HttpServerExchange> DRAIN_CALLBACK = new BiConsumer<InputChannel, HttpServerExchange>() {
        @Override
        public void accept(InputChannel channel, HttpServerExchange exchange) {
            while (channel.isReadable()) {
                try {
                    if(channel.readAsync() == null) {
                        return;
                    }
                } catch (IOException e) {
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                    exchange.delegate.close();
                }
            }
            channel.setReadHandler(this, exchange);
        }
    };

    private int exchangeCompletionListenersCount = 0;
    private ExchangeCompletionListener[] exchangeCompleteListeners;
    private DefaultResponseListener[] defaultResponseListeners;


    private int responseCommitListenerCount;
    private ResponseCommitListener[] responseCommitListeners;

    private int writeFunctionCount;
    private WriteFunction[] writeFunctions;

    private Map<String, Deque<String>> queryParameters;
    private Map<String, Deque<String>> pathParameters;

    private Map<String, Cookie> requestCookies;
    private Map<String, Cookie> responseCookies;

    private BlockingHttpExchange blockingHttpExchange;

    private String protocol;

    /**
     * The security context
     */
    private SecurityContext securityContext;

    // mutable state

    private int state = 200;
    private String requestMethod;

    private String requestScheme;

    /**
     * The original request URI. This will include the host name if it was specified by the client.
     * <p>
     * This is not decoded in any way, and does not include the query string.
     * <p>
     * Examples:
     * GET http://localhost:8080/myFile.jsf?foo=bar HTTP/1.1 -> 'http://localhost:8080/myFile.jsf'
     * POST /my+File.jsf?foo=bar HTTP/1.1 -> '/my+File.jsf'
     */
    private String requestURI;

    /**
     * The request path. This will be decoded by the server, and does not include the query string.
     * <p>
     * This path is not canonicalised, so care must be taken to ensure that escape attacks are not possible.
     * <p>
     * Examples:
     * GET http://localhost:8080/b/../my+File.jsf?foo=bar HTTP/1.1 -> '/b/../my+File.jsf'
     * POST /my+File.jsf?foo=bar HTTP/1.1 -> '/my File.jsf'
     */
    private String requestPath;

    /**
     * The remaining unresolved portion of request path. If a {@link io.undertow.server.handlers.CanonicalPathHandler} is
     * installed this will be canonicalised.
     * <p>
     * Initially this will be equal to {@link #requestPath}, however it will be modified as handlers resolve the path.
     */
    private String relativePath;

    /**
     * The resolved part of the canonical path.
     */
    private String resolvedPath = "";

    /**
     * the query string
     */
    private String queryString = "";

    private long requestStartTime = -1;

    /**
     * The maximum entity size. This can be modified before the request stream is obtained, however once the request
     * stream is obtained this cannot be modified further.
     * <p>
     * The default value for this is determined by the {@link UndertowOptions#MAX_ENTITY_SIZE} option. A value
     * of 0 indicates that this is unbounded.
     * <p>
     * If this entity size is exceeded the request channel will be forcibly closed.
     * <p>
     * TODO: integrate this with HTTP 100-continue responses, to make it possible to send a 417 rather than just forcibly
     * closing the channel.
     *
     * @see UndertowOptions#MAX_ENTITY_SIZE
     */
    private long maxEntitySize;

    /**
     * When the call stack return this task will be executed by the executor specified in {@link #dispatchExecutor}.
     * If the executor is null then it will be executed by the worker.
     */
    private Runnable dispatchTask;

    /**
     * The executor that is to be used to dispatch the {@link #dispatchTask}. Note that this is not cleared
     * between dispatches, so once a request has been dispatched once then all subsequent dispatches will use
     * the same executor.
     */
    private Executor dispatchExecutor;

    /**
     * The number of bytes that have been sent to the remote client. This does not include headers,
     * only the entity body, and does not take any transfer or content encoding into account.
     */
    private long responseBytesSent = 0;

    /**
     * Flag that is set when the response sending begins
     */
    private static final int FLAG_RESPONSE_SENT = 1 << 10;

    /**
     * Flag that is set if this is a persistent connection, and the
     * connection should be re-used.
     */
    private static final int FLAG_PERSISTENT = 1 << 14;

    /**
     * If this flag is set it means that the request has been dispatched,
     * and will not be ending when the call stack returns.
     * <p>
     * This could be because it is being dispatched to a worker thread from
     * an IO thread, or because resume(Reads/Writes) has been called.
     */
    private static final int FLAG_DISPATCHED = 1 << 15;

    /**
     * Flag that is set if the {@link #requestURI} field contains the hostname.
     */
    private static final int FLAG_URI_CONTAINS_HOST = 1 << 16;

    /**
     * Flag that indicates the user has started to read data from the request
     */
    private static final int FLAG_REQUEST_READ = 1 << 20;

    /**
     * Flag that indicates that the request channel has been reset, can be called again
     */
    private static final int FLAG_REQUEST_RESET = 1 << 21;

    /**
     * Flag that indicates that the last data has been queued
     */
    private static final int FLAG_LAST_DATA_QUEUED = 1 << 22;

    /**
     * The source address for the request. If this is null then the actual source address from the channel is used
     */
    private InetSocketAddress sourceAddress;

    /**
     * The destination address for the request. If this is null then the actual source address from the channel is used
     */
    private InetSocketAddress destinationAddress;

    private SSLSessionInfo sslSessionInfo;
    final HttpExchange delegate;
    private boolean executingHandlerChain;

    public HttpServerExchange(final HttpExchange delegate, long maxEntitySize) {
        this.maxEntitySize = maxEntitySize;
        this.delegate = delegate;
        delegate.setCompletedListener(this);
    }

    /**
     * Get the request getProtocol string.  Normally this is one of the strings listed in {@link HttpProtocolNames}.
     *
     * @return the request getProtocol string
     */
    public String getProtocol() {
        if(protocol != null) {
            return protocol;
        } else {
            return delegate.getProtocol();
        }
    }

    /**
     * Sets the http getProtocol
     *
     * @param protocol
     */
    public HttpServerExchange protocol(final String protocol) {
        this.protocol = protocol;
        return this;
    }

    public boolean isSecure() {
        Boolean secure = getAttachment(SECURE_REQUEST);
        if (secure != null && secure) {
            return true;
        }
        String scheme = getRequestScheme();
        if (scheme != null && scheme.equalsIgnoreCase(HTTPS)) {
            return true;
        }
        return false;
    }

    /**
     * Get the HTTP request method.  Normally this is one of the strings listed in {@link HttpMethodNames}.
     *
     * @return the HTTP request method
     */
    public String getRequestMethod() {
        if(requestMethod == null) {
            return delegate.getRequestMethod();
        }
        return requestMethod;
    }

    /**
     * Set the HTTP request method.
     *
     * @param requestMethod the HTTP request method
     */
    public HttpServerExchange requestMethod(final String requestMethod) {
        this.requestMethod = requestMethod;
        return this;
    }

    /**
     * Get the request URI scheme.  Normally this is one of {@code http} or {@code https}.
     *
     * @return the request URI scheme
     */
    public String getRequestScheme() {
        if(requestScheme == null) {
            return delegate.getRequestScheme();
        }
        return requestScheme;
    }

    /**
     * Set the request URI scheme.
     *
     * @param requestScheme the request URI scheme
     */
    public HttpServerExchange setRequestScheme(final String requestScheme) {
        this.requestScheme = requestScheme;
        return this;
    }

    /**
     * The original request URI. This will include the host name, getProtocol etc
     * if it was specified by the client.
     * <p>
     * This is not decoded in any way, and does not include the query string.
     * <p>
     * Examples:
     * GET http://localhost:8080/myFile.jsf?foo=bar HTTP/1.1 -&gt; 'http://localhost:8080/myFile.jsf'
     * POST /my+File.jsf?foo=bar HTTP/1.1 -&gt; '/my+File.jsf'
     */
    public String getRequestURI() {
        if(requestURI == null) {
            return delegate.getRequestURI();
        }
        return requestURI;
    }

    /**
     * Sets the request URI
     *
     * @param requestURI The new request URI
     */
    public HttpServerExchange setRequestURI(final String requestURI) {
        this.requestURI = requestURI;
        return this;
    }

    /**
     * Sets the request URI
     *
     * @param requestURI   The new request URI
     * @param containsHost If this is true the request URI contains the host part
     */
    public HttpServerExchange setRequestURI(final String requestURI, boolean containsHost) {
        this.requestURI = requestURI;
        if (containsHost) {
            this.state |= FLAG_URI_CONTAINS_HOST;
        } else {
            this.state &= ~FLAG_URI_CONTAINS_HOST;
        }
        return this;
    }

    /**
     * If a request was submitted to the server with a full URI instead of just a path this
     * will return true. For example:
     * <p>
     * GET http://localhost:8080/b/../my+File.jsf?foo=bar HTTP/1.1 -&gt; true
     * POST /my+File.jsf?foo=bar HTTP/1.1 -&gt; false
     *
     * @return <code>true</code> If the request URI contains the host part of the URI
     */
    public boolean isHostIncludedInRequestURI() {
        return anyAreSet(state, FLAG_URI_CONTAINS_HOST);
    }


    /**
     * The request path. This will be decoded by the server, and does not include the query string.
     * <p>
     * This path is not canonicalised, so care must be taken to ensure that escape attacks are not possible.
     * <p>
     * Examples:
     * GET http://localhost:8080/b/../my+File.jsf?foo=bar HTTP/1.1 -&gt; '/b/../my+File.jsf'
     * POST /my+File.jsf?foo=bar HTTP/1.1 -&gt; '/my File.jsf'
     */
    public String getRequestPath() {
        return requestPath;
    }

    /**
     * Set the request URI path.
     *
     * @param requestPath the request URI path
     */
    public HttpServerExchange setRequestPath(final String requestPath) {
        this.requestPath = requestPath;
        return this;
    }

    /**
     * Get the request relative path.  This is the path which should be evaluated by the current handler.
     * <p>
     * If the {@link io.undertow.server.handlers.CanonicalPathHandler} is installed in the current chain
     * then this path with be canonicalized
     *
     * @return the request relative path
     */
    public String getRelativePath() {
        return relativePath;
    }

    /**
     * Set the request relative path.
     *
     * @param relativePath the request relative path
     */
    public HttpServerExchange setRelativePath(final String relativePath) {
        this.relativePath = relativePath;
        return this;
    }

    /**
     * Get the resolved path.
     *
     * @return the resolved path
     */
    public String getResolvedPath() {
        return resolvedPath;
    }

    /**
     * Set the resolved path.
     *
     * @param resolvedPath the resolved path
     */
    public HttpServerExchange setResolvedPath(final String resolvedPath) {
        this.resolvedPath = resolvedPath;
        return this;
    }

    /**
     * @return The query string, without the leading ?
     */
    public String getQueryString() {
        return queryString;
    }

    public HttpServerExchange setQueryString(final String queryString) {
        this.queryString = queryString;
        return this;
    }

    /**
     * Reconstructs the complete URL as seen by the user. This includes scheme, host name etc,
     * but does not include query string.
     * <p>
     * This is not decoded.
     */
    public String getRequestURL() {
        if (isHostIncludedInRequestURI()) {
            return getRequestURI();
        } else {
            return getRequestScheme() + "://" + getHostAndPort() + getRequestURI();
        }
    }

    public long getRequestContentLength() {
        return delegate.getRequestContentLength();
    }

    /**
     * @return The content length of the response, or <code>-1</code> if it has not been set
     */
    public long getResponseContentLength() {
        return delegate.getResponseContentLength();
    }
    /**
     * Return the host that this request was sent to, in general this will be the
     * value of the Host header, minus the port specifier.
     * <p>
     * If this resolves to an IPv6 address it will not be enclosed by square brackets.
     * Care must be taken when constructing URLs based on this method to ensure IPv6 URLs
     * are handled correctly.
     *
     * @return The host part of the destination address
     */
    public String getHostName() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host == null) {
            host = getDestinationAddress().getHostString();
        } else {
            if (host.startsWith("[")) {
                host = host.substring(1, host.indexOf(']'));
            } else if (host.indexOf(':') != -1) {
                host = host.substring(0, host.indexOf(':'));
            }
        }
        return host;
    }


    /**
     * Return the host, and also the port if this request was sent to a non-standard port. In general
     * this will just be the value of the Host header.
     * <p>
     * If this resolves to an IPv6 address it *will*  be enclosed by square brackets. The return
     * value of this method is suitable for inclusion in a URL.
     *
     * @return The host and port part of the destination address
     */
    public String getHostAndPort() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host == null) {
            InetSocketAddress address = getDestinationAddress();
            host = NetworkUtils.formatPossibleIpv6Address(address.getHostString());
            int port = address.getPort();
            if (!((getRequestScheme().equals("http") && port == 80)
                    || (getRequestScheme().equals("https") && port == 443))) {
                host = host + ":" + port;
            }
        }
        return host;
    }

    /**
     * Return the port that this request was sent to. In general this will be the value of the Host
     * header, minus the host name.
     *
     * @return The port part of the destination address
     */
    public int getHostPort() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host != null) {
            //for ipv6 addresses we make sure we take out the first part, which can have multiple occurrences of :
            final int colonIndex;
            if (host.startsWith("[")) {
                colonIndex = host.indexOf(':', host.indexOf(']'));
            } else {
                colonIndex = host.indexOf(':');
            }
            if (colonIndex != -1) {
                try {
                    return Integer.parseInt(host.substring(colonIndex + 1));
                } catch (NumberFormatException ignore) {
                }
            }
            if (getRequestScheme().equals("https")) {
                return 443;
            } else if (getRequestScheme().equals("http")) {
                return 80;
            }

        }
        return getDestinationAddress().getPort();
    }

    public boolean isPersistent() {
        return anyAreSet(state, FLAG_PERSISTENT);
    }

    /**
     * @return <code>true</code> If the current thread in the IO thread for the exchange
     */
    public boolean isInIoThread() {
        return getIoThread().inEventLoop();
    }

    /**
     * @return The number of bytes sent in the entity body
     */
    public long getResponseBytesSent() {
        if (Connectors.isEntityBodyAllowed(this) && !getRequestMethod().equals(HttpMethodNames.HEAD)) {
            return responseBytesSent;
        } else {
            return 0; //body is not allowed, even if we attempt to write it will be ignored
        }
    }

    /**
     * Updates the number of response bytes sent. Used when compression is in use
     *
     * @param bytes The number of bytes to increase the response size by. May be negative
     */
    void updateBytesSent(long bytes) {
        if (Connectors.isEntityBodyAllowed(this) && !getRequestMethod().equals(HttpMethodNames.HEAD)) {
            responseBytesSent += bytes;
        }
    }

    public HttpServerExchange setPersistent(final boolean persistent) {
        if (persistent) {
            this.state = this.state | FLAG_PERSISTENT;
        } else {
            this.state = this.state & ~FLAG_PERSISTENT;
        }
        return this;
    }

    public boolean isDispatched() {
        return anyAreSet(state, FLAG_DISPATCHED);
    }

    public HttpServerExchange unDispatch() {
        state &= ~FLAG_DISPATCHED;
        dispatchTask = null;
        return this;
    }

    /**
     * Dispatches this request to the XNIO worker thread pool. Once the call stack returns
     * the given runnable will be submitted to the executor.
     * <p>
     * In general handlers should first check the value of {@link #isInIoThread()} before
     * calling this method, and only dispatch if the request is actually running in the IO
     * thread.
     *
     * @param runnable The task to run
     * @throws IllegalStateException If this exchange has already been dispatched
     */
    public HttpServerExchange dispatch(final Runnable runnable) {
        dispatch(null, runnable);
        return this;
    }

    /**
     * Dispatches this request to the given executor. Once the call stack returns
     * the given runnable will be submitted to the executor.
     * <p>
     * In general handlers should first check the value of {@link #isInIoThread()} before
     * calling this method, and only dispatch if the request is actually running in the IO
     * thread.
     *
     * @param runnable The task to run
     * @throws IllegalStateException If this exchange has already been dispatched
     */
    public HttpServerExchange dispatch(final Executor executor, final Runnable runnable) {
        if (isExecutingHandlerChain()) {
            if (executor != null) {
                this.dispatchExecutor = executor;
            }
            state |= FLAG_DISPATCHED;
            if (delegate.isIoOperationQueued()) {
                throw UndertowMessages.MESSAGES.resumedAndDispatched();
            }
            this.dispatchTask = runnable;
        } else {
            if (executor == null) {
                delegate.getWorker().execute(runnable);
            } else {
                executor.execute(runnable);
            }
        }
        return this;
    }

    boolean isExecutingHandlerChain() {
        return executingHandlerChain;
    }

    public HttpServerExchange dispatch(final HttpHandler handler) {
        dispatch(null, handler);
        return this;
    }

    public HttpServerExchange dispatch(final Executor executor, final HttpHandler handler) {
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Connectors.executeRootHandler(handler, HttpServerExchange.this);
            }
        };
        dispatch(executor, runnable);
        return this;
    }

    /**
     * Sets the executor that is used for dispatch operations where no executor is specified.
     *
     * @param executor The executor to use
     */
    public HttpServerExchange setDispatchExecutor(final Executor executor) {
        if (executor == null) {
            dispatchExecutor = null;
        } else {
            dispatchExecutor = executor;
        }
        return this;
    }

    /**
     * Gets the current executor that is used for dispatch operations. This may be null
     *
     * @return The current dispatch executor
     */
    public Executor getDispatchExecutor() {
        return dispatchExecutor;
    }

    /**
     * @return The current dispatch task
     */
    Runnable getDispatchTask() {
        return dispatchTask;
    }

    public HttpServerExchange addExchangeCompleteListener(final ExchangeCompletionListener listener) {
        if (isComplete() || this.exchangeCompletionListenersCount == -1) {
            throw UndertowMessages.MESSAGES.exchangeAlreadyComplete();
        }
        final int exchangeCompletionListenersCount = this.exchangeCompletionListenersCount++;
        ExchangeCompletionListener[] exchangeCompleteListeners = this.exchangeCompleteListeners;
        if (exchangeCompleteListeners == null || exchangeCompleteListeners.length == exchangeCompletionListenersCount) {
            ExchangeCompletionListener[] old = exchangeCompleteListeners;
            this.exchangeCompleteListeners = exchangeCompleteListeners = new ExchangeCompletionListener[exchangeCompletionListenersCount + 2];
            if (old != null) {
                System.arraycopy(old, 0, exchangeCompleteListeners, 0, exchangeCompletionListenersCount);
            }
        }
        exchangeCompleteListeners[exchangeCompletionListenersCount] = listener;
        return this;
    }

    public HttpServerExchange addDefaultResponseListener(final DefaultResponseListener listener) {
        int i = 0;
        if (defaultResponseListeners == null) {
            defaultResponseListeners = new DefaultResponseListener[2];
        } else {
            while (i != defaultResponseListeners.length && defaultResponseListeners[i] != null) {
                ++i;
            }
            if (i == defaultResponseListeners.length) {
                DefaultResponseListener[] old = defaultResponseListeners;
                defaultResponseListeners = new DefaultResponseListener[defaultResponseListeners.length + 2];
                System.arraycopy(old, 0, defaultResponseListeners, 0, old.length);
            }
        }
        defaultResponseListeners[i] = listener;
        return this;
    }

    /**
     * Get the source address of the HTTP request.
     *
     * @return the source address of the HTTP request
     */
    public InetSocketAddress getSourceAddress() {
        if (sourceAddress != null) {
            return sourceAddress;
        }
        return delegate.getSourceAddress();
    }

    public boolean isComplete() {
        return delegate.isComplete();
    }

    public boolean isRequestComplete() {
        return delegate.isRequestComplete();
    }

    public boolean isResponseComplete() {
        return delegate.isResponseComplete();
    }

    /**
     * Sets the source address of the HTTP request. If this is not explicitly set
     * the actual source address of the channel is used.
     *
     * @param sourceAddress The address
     */
    public HttpServerExchange setSourceAddress(InetSocketAddress sourceAddress) {
        this.sourceAddress = sourceAddress;
        return this;
    }

    /**
     * Get the destination address of the HTTP request.
     *
     * @return the destination address of the HTTP request
     */
    public InetSocketAddress getDestinationAddress() {
        if (destinationAddress != null) {
            return destinationAddress;
        }
        return delegate.getDestinationAddress();
    }

    /**
     * Sets the destination address of the HTTP request. If this is not explicitly set
     * the actual destination address of the channel is used.
     *
     * @param destinationAddress The address
     */
    public HttpServerExchange setDestinationAddress(InetSocketAddress destinationAddress) {
        this.destinationAddress = destinationAddress;
        return this;
    }


    /**
     * Sets the response content length
     *
     * @param length The content length
     */
    public HttpServerExchange setResponseContentLength(long length) {
        if (length == -1) {
            delegate.removeResponseHeader(HttpHeaderNames.CONTENT_LENGTH);
        } else {
            delegate.setResponseHeader(HttpHeaderNames.CONTENT_LENGTH, Long.toString(length));
        }
        return this;
    }

    /**
     * Returns a mutable map of query parameters.
     *
     * @return The query parameters
     */
    public Map<String, Deque<String>> getQueryParameters() {
        if (queryParameters == null) {
            queryParameters = new TreeMap<>();
        }
        return queryParameters;
    }

    public HttpServerExchange addQueryParam(final String name, final String param) {
        if (queryParameters == null) {
            queryParameters = new TreeMap<>();
        }
        Deque<String> list = queryParameters.get(name);
        if (list == null) {
            queryParameters.put(name, list = new ArrayDeque<>(2));
        }
        list.add(param);
        return this;
    }


    /**
     * Returns a mutable map of path parameters
     *
     * @return The path parameters
     */
    public Map<String, Deque<String>> getPathParameters() {
        if (pathParameters == null) {
            pathParameters = new TreeMap<>();
        }
        return pathParameters;
    }

    public HttpServerExchange addPathParam(final String name, final String param) {
        if (pathParameters == null) {
            pathParameters = new TreeMap<>();
        }
        Deque<String> list = pathParameters.get(name);
        if (list == null) {
            pathParameters.put(name, list = new ArrayDeque<>(2));
        }
        list.add(param);
        return this;
    }

    /**
     * @return A mutable map of request cookies
     */
    public Map<String, Cookie> getRequestCookies() {
        if (requestCookies == null) {
            requestCookies = Cookies.parseRequestCookies(
                    delegate.getUndertowOptions().get(UndertowOptions.MAX_COOKIES, 200),
                    delegate.getUndertowOptions().get(UndertowOptions.ALLOW_EQUALS_IN_COOKIE_VALUE, false),
                    delegate.getRequestHeaders(HttpHeaderNames.COOKIE));
        }
        return requestCookies;
    }

    /**
     * Sets a response cookie
     *
     * @param cookie The cookie
     */
    public HttpServerExchange setResponseCookie(final Cookie cookie) {
        if (delegate.getUndertowOptions().get(UndertowOptions.ENABLE_RFC6265_COOKIE_VALIDATION, UndertowOptions.DEFAULT_ENABLE_RFC6265_COOKIE_VALIDATION)) {
            if (cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                Rfc6265CookieSupport.validateCookieValue(cookie.getValue());
            }
            if (cookie.getPath() != null && !cookie.getPath().isEmpty()) {
                Rfc6265CookieSupport.validatePath(cookie.getPath());
            }
            if (cookie.getDomain() != null && !cookie.getDomain().isEmpty()) {
                Rfc6265CookieSupport.validateDomain(cookie.getDomain());
            }
        }
        if (responseCookies == null) {
            responseCookies = new TreeMap<>(); //hashmap is slow to allocate in JDK7
        }
        responseCookies.put(cookie.getName(), cookie);
        return this;
    }

    /**
     * @return A mutable map of response cookies
     */
    public Map<String, Cookie> getResponseCookies() {
        if (responseCookies == null) {
            responseCookies = new TreeMap<>();
        }
        return responseCookies;
    }

    /**
     * For internal use only
     *
     * @return The response cookies, or null if they have not been set yet
     */
    Map<String, Cookie> getResponseCookiesInternal() {
        return responseCookies;
    }

    /**
     * @return <code>true</code> If the response has already been started
     */
    public boolean isResponseStarted() {
        return allAreSet(state, FLAG_RESPONSE_SENT);
    }


    /**
     * Reads some data. If all data has been read it will return null.
     *
     * @return
     * @throws IOException on failure
     */
    public ByteBuf readBlocking() throws IOException {
        if (isRequestComplete()) {
            return null;
        }
        return delegate.getInputChannel().readBlocking();
    }

    public void send1ContinueIfRequired() {
        if(HttpContinue.requiresContinueResponse(this)) {
            delegate.sendContinue();
        }
    }


    /**
     * Writes the given UTF-8 data and ends the exchange
     *
     * @param data The data to write
     */
    public void writeAsync(String data) {
        writeAsync(Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), true, IoCallback.END_EXCHANGE, null);
    }

    /**
     * Writes the given  data in the provided charset and ends the exchange
     *
     * @param data The data to write
     */
    public void writeAsync(String data, Charset charset) {
        writeAsync(Unpooled.copiedBuffer(data, charset), true, IoCallback.END_EXCHANGE, null);
    }

    /**
     * Writes the given data in the provided charset and invokes the provided callback on completion
     *
     * @param data The data to write
     */
    public <T> void writeAsync(String data, Charset charset, boolean last, IoCallback<T> callback, T context) {
        writeAsync(Unpooled.copiedBuffer(data, charset), last, callback, context);
    }

    public <T> void writeAsync(ByteBuf data, boolean last, IoCallback<T> callback, T context) {
        if (data == null && !last) {
            throw new IllegalArgumentException("cannot call write with a null buffer and last being false");
        }
        if (isResponseComplete() || anyAreSet(state, FLAG_LAST_DATA_QUEUED)) {
            if (last && data == null) {
                callback.onComplete(delegate, context);
                return;
            }
            callback.onException(delegate, context, new IOException(UndertowMessages.MESSAGES.responseComplete()));
            return;
        }
        if (last) {
            state |= FLAG_LAST_DATA_QUEUED;
        }

        if(data != null) {
            updateBytesSent(data.readableBytes());
        }
        handleFirstData();
        if (writeFunctions != null) {
            for (int i = 0; i < writeFunctionCount; ++i) {
                data = writeFunctions[i].preWrite(data, last);
            }
        }
        delegate.getOutputChannel().writeAsync(data, last,  callback, context);
    }

    public void writeBlocking(ByteBuf data, boolean last) throws IOException {
        if (data == null && !last) {
            throw new IllegalArgumentException("cannot call write with a null buffer and last being false");
        }
        if (isResponseComplete() || anyAreSet(state,  FLAG_LAST_DATA_QUEUED)) {
            if (last && data == null) {
                return;
            }
            throw UndertowMessages.MESSAGES.responseComplete();
        }
        if (last) {
            state |= FLAG_LAST_DATA_QUEUED;
        }
        if(data != null) {
            updateBytesSent(data.readableBytes());
        }
        handleFirstData();
        if (writeFunctions != null) {
            for (int i = 0; i < writeFunctionCount; ++i) {
                data = writeFunctions[i].preWrite(data, last);
            }
        }
        delegate.getOutputChannel().writeBlocking(data, last);
    }

    private void handleFirstData() {
        if (anyAreClear(state, FLAG_RESPONSE_SENT)) {
            for (int i = responseCommitListenerCount - 1; i >= 0; --i) {
                responseCommitListeners[i].beforeCommit(this);
            }
            state |= FLAG_RESPONSE_SENT;
            Connectors.flattenCookies(this);
        }
    }

    private void invokeExchangeCompleteListeners() {
        while (exchangeCompletionListenersCount > 0) {
            int i = exchangeCompletionListenersCount - 1;
            ExchangeCompletionListener next = exchangeCompleteListeners[i];
            exchangeCompletionListenersCount = -1;
            next.exchangeEvent(this);
        }
    }

    /**
     * Get the status code.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return delegate.getStatusCode();
    }

    public String getRequestHeader(String name) {
        return delegate.getRequestHeader(name);
    }

    public List<String> getRequestHeaders(String name) {
        return delegate.getRequestHeaders(name);
    }

    public boolean containsRequestHeader(String name) {
        return delegate.containsRequestHeader(name);
    }

    public void removeRequestHeader(String name) {
        delegate.removeRequestHeader(name);
    }

    public void setRequestHeader(String name, String value) {
        delegate.setRequestHeader(name, value);
    }

    public Collection<String> getRequestHeaderNames() {
        return delegate.getRequestHeaderNames();
    }

    public void addRequestHeader(String name, String value) {
        delegate.addRequestHeader(name, value);
    }

    public void clearRequestHeaders() {
        delegate.clearRequestHeaders();
    }

    public void clearResponseHeaders() {
        delegate.clearResponseHeaders();
    }

    public String getResponseHeader(String name) {
        return delegate.getResponseHeader(name);
    }

    public List<String> getResponseHeaders(String name) {
        return delegate.getResponseHeaders(name);
    }

    public boolean containsResponseHeader(String name) {
        return delegate.containsResponseHeader(name);
    }

    public void removeResponseHeader(String name) {
        delegate.removeResponseHeader(name);
    }

    public void setResponseHeader(String name, String value) {
        delegate.setResponseHeader(name, value);
    }

    public Collection<String> getResponseHeaderNames() {
        return delegate.getResponseHeaderNames();
    }

    public void addResponseHeader(String name, String value) {
        delegate.addResponseHeader(name, value);
    }

    /**
     * Change the status code for this response.  If not specified, the code will be a {@code 200}.  Setting
     * the status code after the response headers have been transmitted has no effect.
     *
     * @param statusCode the new code
     * @throws IllegalStateException if a response or upgrade was already sent
     */
    public HttpServerExchange setStatusCode(final int statusCode) {
        if (statusCode < 0 || statusCode > 999) {
            throw new IllegalArgumentException("Invalid response code");
        }
        int oldVal = state;
        if (allAreSet(oldVal, FLAG_RESPONSE_SENT)) {
            throw UndertowMessages.MESSAGES.responseAlreadyStarted();
        }
        if (statusCode >= 500) {
            if (UndertowLogger.ERROR_RESPONSE.isDebugEnabled()) {
                UndertowLogger.ERROR_RESPONSE.debugf(new RuntimeException(), "Setting error code %s for exchange %s", statusCode, this);
            }
        }
        delegate.setStatusCode(statusCode);
        return this;
    }

    /**
     * Calling this method puts the exchange in blocking mode, and creates a
     * {@link BlockingHttpExchange} object to store the streams.
     * <p>
     * When an exchange is in blocking mode the input stream methods become
     * available, other than that there is presently no major difference
     * between blocking an non-blocking modes.
     *
     * @return The existing blocking exchange, if any
     */
    public BlockingHttpExchange startBlocking() {
        final BlockingHttpExchange old = this.blockingHttpExchange;
        blockingHttpExchange = new DefaultBlockingHttpExchange(this);
        return old;
    }

    /**
     * Calling this method puts the exchange in blocking mode, using the given
     * blocking exchange as the source of the streams.
     * <p>
     * When an exchange is in blocking mode the input stream methods become
     * available, other than that there is presently no major difference
     * between blocking an non-blocking modes.
     * <p>
     * Note that this method may be called multiple times with different
     * exchange objects, to allow handlers to modify the streams
     * that are being used.
     *
     * @return The existing blocking exchange, if any
     */
    public BlockingHttpExchange startBlocking(final BlockingHttpExchange httpExchange) {
        final BlockingHttpExchange old = this.blockingHttpExchange;
        blockingHttpExchange = httpExchange;
        return old;
    }

    /**
     * Returns true if {@link #startBlocking()} or {@link #startBlocking(BlockingHttpExchange)} has been called.
     *
     * @return <code>true</code> If this is a blocking HTTP server exchange
     */
    public boolean isBlocking() {
        return blockingHttpExchange != null;
    }

    /**
     * @return The input stream
     * @throws IllegalStateException if {@link #startBlocking()} has not been called
     */
    public InputStream getInputStream() {
        if (blockingHttpExchange == null) {
            throw UndertowMessages.MESSAGES.startBlockingHasNotBeenCalled();
        }
        return blockingHttpExchange.getInputStream();
    }

    /**
     * @return The output stream
     * @throws IllegalStateException if {@link #startBlocking()} has not been called
     */
    public OutputStream getOutputStream() {
        if (blockingHttpExchange == null) {
            throw UndertowMessages.MESSAGES.startBlockingHasNotBeenCalled();
        }
        return blockingHttpExchange.getOutputStream();
    }


    /**
     * @return The request start time, or -1 if this was not recorded
     */
    public long getRequestStartTime() {
        return requestStartTime;
    }


    HttpServerExchange setRequestStartTime(long requestStartTime) {
        this.requestStartTime = requestStartTime;
        return this;
    }

    /**
     * Ends the exchange by fully draining the request channel, and flushing the response channel.
     * <p>
     * This can result in handoff to an XNIO worker, so after this method is called the exchange should
     * not be modified by the caller.
     * <p>
     * If the exchange is already complete this method is a noop
     */
    public HttpServerExchange endExchange() {
        final int state = this.state;
        if (isRequestComplete() && isResponseComplete()) {
            if (blockingHttpExchange != null) {
                //we still have to close the blocking exchange in this case,
                if (isInIoThread()) {
                    dispatch(new Runnable() {
                        @Override
                        public void run() {
                            endExchange();
                        }
                    });
                    return this;
                }
                IoUtils.safeClose(blockingHttpExchange);
            }
            return this;
        }
        if (defaultResponseListeners != null) {
            int i = defaultResponseListeners.length - 1;
            while (i >= 0) {
                DefaultResponseListener listener = defaultResponseListeners[i];
                if (listener != null) {
                    defaultResponseListeners[i] = null;
                    try {
                        if (listener.handleDefaultResponse(this)) {
                            return this;
                        }
                    } catch (Throwable e) {
                        UndertowLogger.REQUEST_LOGGER.debug("Exception running default response listener", e);
                    }
                }
                i--;
            }
        }

        if (blockingHttpExchange != null) {
            //we still have to close the blocking exchange in this case,
            if (isInIoThread()) {
                dispatch(new Runnable() {
                    @Override
                    public void run() {
                        endExchange();
                    }
                });
                return this;
            }
            IoUtils.safeClose(blockingHttpExchange);

            try {
                //TODO: can we end up in this situation in a IO thread?
                //this will end the exchange in a blocking manner
                blockingHttpExchange.close();
            } catch (IOException e) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                delegate.close();
            } catch (Throwable t) {
                UndertowLogger.REQUEST_IO_LOGGER.handleUnexpectedFailure(t);
                delegate.close();
            }
        }
        if (!isRequestComplete()) {
            DRAIN_CALLBACK.accept(this, this);
        }

        if (!isResponseComplete() && allAreClear(state, FLAG_LAST_DATA_QUEUED)) {
            writeAsync(null, true, IoCallback.END_EXCHANGE, null);
        }
        return this;
    }

    public EventExecutor getIoThread() {
        return delegate.getIoThread();
    }

    /**
     * @return The maximum entity size for this exchange
     */
    public long getMaxEntitySize() {
        return maxEntitySize;
    }

    /**
     * Sets the max entity size for this exchange. This cannot be modified after the request channel has been obtained.
     *
     * @param maxEntitySize The max entity size
     */
    public HttpServerExchange setMaxEntitySize(final long maxEntitySize) {
        if (anyAreSet(state, FLAG_REQUEST_READ)) {
            throw UndertowMessages.MESSAGES.requestChannelAlreadyProvided();
        }
        this.maxEntitySize = maxEntitySize;
        delegate.setMaxEntitySize(maxEntitySize);
        return this;
    }

    public SecurityContext getSecurityContext() {
        return securityContext;
    }

    public void setSecurityContext(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    /**
     * Adds a listener that will be invoked on response commit
     *
     * @param listener The response listener
     */
    public void addResponseCommitListener(final ResponseCommitListener listener) {
        if (isComplete() || this.responseCommitListenerCount == -1) {
            throw UndertowMessages.MESSAGES.responseAlreadyStarted();
        }
        final int responseCommitListenerCount = this.responseCommitListenerCount++;
        ResponseCommitListener[] responseCommitListeners = this.responseCommitListeners;
        if (responseCommitListeners == null || responseCommitListeners.length == responseCommitListenerCount) {
            ResponseCommitListener[] old = responseCommitListeners;
            this.responseCommitListeners = responseCommitListeners = new ResponseCommitListener[responseCommitListenerCount + 2];
            if (old != null) {
                System.arraycopy(old, 0, responseCommitListeners, 0, responseCommitListenerCount);
            }
        }
        responseCommitListeners[responseCommitListenerCount] = listener;
    }

    public void addWriteFunction(final WriteFunction listener) {
        final int writeFunctionCount = this.writeFunctionCount++;
        WriteFunction[] writeFunctions = this.writeFunctions;
        if (writeFunctions == null || writeFunctions.length == writeFunctionCount) {
            WriteFunction[] old = writeFunctions;
            this.writeFunctions = writeFunctions = new WriteFunction[writeFunctionCount + 2];
            if (old != null) {
                System.arraycopy(old, 0, writeFunctions, 0, writeFunctionCount);
            }
        }
        writeFunctions[writeFunctionCount] = listener;
    }

    @Override
    public ByteBuf readAsync() throws IOException {
        return delegate.getInputChannel().readAsync();
    }

    @Override
    public boolean isReadable() {
        return delegate.getInputChannel().isReadable();
    }

    @Override
    public <T> void setReadHandler(BiConsumer<InputChannel, T> handler, T context) {
        delegate.getInputChannel().setReadHandler(handler, context);
    }

    public int readBytesAvailable() {
        return delegate.getInputChannel().readBytesAvailable();
    }

    @Override
    public ByteBuf allocateBuffer() {
        return delegate.getBufferAllocator().allocateBuffer();
    }

    @Override
    public ByteBuf allocateBuffer(boolean direct) {
        return delegate.getBufferAllocator().allocateBuffer(direct);
    }

    @Override
    public ByteBuf allocateBuffer(int bufferSize) {
        return delegate.getBufferAllocator().allocateBuffer(bufferSize);
    }

    @Override
    public ByteBuf allocateBuffer(boolean direct, int bufferSize) {
        return delegate.getBufferAllocator().allocateBuffer(direct, bufferSize);
    }

    @Override
    public int getBufferSize() {
        return delegate.getBufferAllocator().getBufferSize();
    }

    /**
     * Used to terminate the request in an async manner, the actual mechanism will depend on the underlying getProtocol,
     * for example HTTP/2 may send a RST_STREAM stream if there is more data coming.
     * <p>
     * This may result in an unclean close if it is called on a non multiplexed getProtocol
     */
    public void discardRequest() {
        if (isRequestComplete()) {
            return;
        }
        delegate.discardRequest();
    }

    /**
     * Upgrade the channel to a raw socket. This method set the response code to 101, and then marks both the
     * request and response as terminated, which means that once the current request is completed the raw channel
     * can be obtained
     *
     * @throws IllegalStateException if a response or upgrade was already sent, or if the request body is already being
     *                               read
     */
    public HttpServerExchange upgradeChannel(final Consumer<Object> listener) {
        if (!delegate.isUpgradeSupported()) {
            throw UndertowMessages.MESSAGES.upgradeNotSupported();
        }
        if (!delegate.containsRequestHeader(HttpHeaderNames.UPGRADE)) {
            throw UndertowMessages.MESSAGES.notAnUpgradeRequest();
        }
        UndertowLogger.REQUEST_LOGGER.debugf("Upgrading request %s", this);

        setStatusCode(StatusCodes.SWITCHING_PROTOCOLS);
        delegate.setResponseHeader(HttpHeaderNames.CONNECTION, HttpHeaderNames.UPGRADE);
        delegate.setUpgradeListener(listener);
        return this;
    }

    /**
     * Upgrade the channel to a raw socket. This method set the response code to 101, and then marks both the
     * request and response as terminated, which means that once the current request is completed the raw channel
     * can be obtained
     *
     * @param productName the product name to report to the client
     * @throws IllegalStateException if a response or upgrade was already sent, or if the request body is already being
     *                               read
     */
    public HttpServerExchange upgradeChannel(String productName, final Consumer<Object> listener) {
        if (!delegate.isUpgradeSupported()) {
            throw UndertowMessages.MESSAGES.upgradeNotSupported();
        }
        UndertowLogger.REQUEST_LOGGER.debugf("Upgrading request %s", this);
        delegate.setUpgradeListener(listener);
        setStatusCode(StatusCodes.SWITCHING_PROTOCOLS);
        delegate.setResponseHeader(HttpHeaderNames.UPGRADE, productName);
        delegate.setResponseHeader(HttpHeaderNames.CONNECTION, HttpHeaderNames.UPGRADE);
        return this;
    }

    public void resetRequestChannel() {
        state |= FLAG_REQUEST_RESET;
    }

    public void setSslSessionInfo(SSLSessionInfo info) {
        this.sslSessionInfo = info;
    }

    public SSLSessionInfo getSslSessionInfo() {
        if (sslSessionInfo == null) {
            return delegate.getSslSessionInfo();
        }
        return sslSessionInfo;
    }

    @Override
    public void close() {
        endExchange();
    }

    public Executor getWorker() {
        return delegate.getWorker();
    }

    public UndertowOptionMap getUndertowOptions() {
        return delegate.getUndertowOptions();
    }

    public boolean isPushSupported() {
        return delegate.isPushSupported();
    }

    public void pushResource(String path, String method, Map<String, List<String>> requestHeaders) {

    }

    public boolean isRequestTrailerFieldsSupported() {
        return delegate.isRequestTrailerFieldsSupported();
    }

    @Override
    public void completed(HttpExchange exchange) {
        invokeExchangeCompleteListeners();
    }

    public void beginExecutingHandlerChain() {
        executingHandlerChain = true;
    }

    public void endExecutingHandlerChain() {
        executingHandlerChain = false;
    }

    public void runResumeReadWrite() {
        //TODO: hold off on queuing IO
    }

    public OutputChannel getOutputChannel() {
        return this;
    }

    public InputChannel getInputChannel() {
        return this;
    }

    private static class DefaultBlockingHttpExchange implements BlockingHttpExchange {

        private InputStream inputStream;
        private UndertowOutputStream outputStream;
        private final HttpServerExchange exchange;

        DefaultBlockingHttpExchange(final HttpServerExchange exchange) {
            this.exchange = exchange;
        }

        public InputStream getInputStream() {
            if (inputStream == null) {
                inputStream = new UndertowInputStream(exchange);
            }
            return inputStream;
        }

        public UndertowOutputStream getOutputStream() {
            if (outputStream == null) {
                outputStream = new UndertowOutputStream(exchange);
            }
            return outputStream;
        }

        @Override
        public void close() throws IOException {
            try {
                getInputStream().close();
            } finally {
                getOutputStream().close();
            }
        }
    }

    @Override
    public String toString() {
        return "HttpServerExchange{ " + getRequestMethod() + " " + getRequestURI() + " delegate " + delegate + '}';
    }
}
