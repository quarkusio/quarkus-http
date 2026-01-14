/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.websockets.vertx;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.undertow.websockets.handshake.WebSocketHttpExchange;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * @author Stuart Douglas
 */
public class VertxWebSocketHttpExchange implements WebSocketHttpExchange {

    private final Executor executor;
    private final HttpServerRequest request;
    private final HttpServerResponse response;
    private final RoutingContext exchange;
    final Map<String, Object> attributes = new HashMap<>();

    public VertxWebSocketHttpExchange(Executor executor, final RoutingContext context) {
        this.executor = executor;
        this.request = context.request();
        this.response = context.response();
        this.exchange = context;
    }

    @Override
    public <T> void putAttachment(final String key, final T value) {
        attributes.put(key, value);
    }

    @Override
    public <T> T getAttachment(final String key) {
        return (T) attributes.get(key);
    }

    @Override
    public String getRequestHeader(final CharSequence headerName) {
        return request.getHeader(headerName.toString());
    }

    @Override
    public Map<String, List<String>> getRequestHeaders() {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> headerNames = request.headers().names();
        for (String header : headerNames) {
            final List<String> vals = request.headers().getAll(header);
            headers.put(header, vals);

        }
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public String getResponseHeader(final CharSequence headerName) {
        return response.headers().get(headerName);
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        Map<String, List<String>> headers = new HashMap<>();
        final Collection<String> headerNames = response.headers().names();
        for (String header : headerNames) {
            headers.put(header, new ArrayList<>(response.headers().getAll(header)));
        }
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public void setResponseHeaders(final Map<String, List<String>> headers) {
        response.headers().clear();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            response.headers().set(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void setResponseHeader(final CharSequence headerName, final String headerValue) {
        response.headers().set(headerName.toString(), headerValue);
    }

    @Override
    public void upgradeChannel(Consumer<Object> listener) {
        response.headers().set(HttpHeaderNames.CONNECTION, "upgrade");

        Http1xServerConnection connection = (Http1xServerConnection) request.connection();
        ChannelHandlerContext context = connection.channelHandlerContext();
        final ChannelHandler websocketChannelHandler = context.pipeline().get("webSocketExtensionHandler");
        if (websocketChannelHandler != null) {
            context.pipeline().remove(websocketChannelHandler);
        }

        response.setStatusCode(101).end()
                .onComplete(new Handler<AsyncResult<Void>>() {
                    @Override
                    public void handle(AsyncResult<Void> event) {
                        Http1xServerConnection connection = (Http1xServerConnection) request.connection();
                        ChannelHandlerContext context = connection.channelHandlerContext();
                        context.pipeline().remove("httpDecoder");
                        context.pipeline().remove("httpEncoder");
                        context.pipeline().remove("handler");
                        listener.accept(context);
                    }
                });
    }

    @Override
    public void endExchange() {
        //noop
    }

    @Override
    public void close() {
        response.end();
    }

    @Override
    public String getRequestScheme() {
        return request.scheme();
    }

    @Override
    public String getRequestURI() {
        return request.uri() + (request.query() == null ? "" : "?" + request.query());
    }

    @Override
    public String getQueryString() {
        return request.query();
    }

    @Override
    public Object getSession() {
        return exchange.session();
    }

    @Override
    public Map<String, List<String>> getRequestParameters() {
        Map<String, List<String>> params = new HashMap<>();
        for (Map.Entry<String, String> param : request.params()) {
            params.put(param.getKey(), new ArrayList<>(request.params().getAll(param.getKey())));
        }
        return params;
    }

    @Override
    public Principal getUserPrincipal() {
        User user = exchange.user();
        if (user != null) {
            return new Principal() {
                @Override
                public String getName() {
                    return user.principal().getString("username");
                }
            };
        }
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }
}
