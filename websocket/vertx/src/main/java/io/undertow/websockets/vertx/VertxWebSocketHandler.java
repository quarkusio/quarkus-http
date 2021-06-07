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

package io.undertow.websockets.vertx;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.undertow.websockets.ConfiguredServerEndpoint;
import io.undertow.websockets.EndpointSessionHandler;
import io.undertow.websockets.ServerWebSocketContainer;
import io.undertow.websockets.UndertowSession;
import io.undertow.websockets.WebSocketDeploymentInfo;
import io.undertow.websockets.handshake.Handshake;
import io.undertow.websockets.handshake.HandshakeUtil;
import io.undertow.websockets.util.WebsocketPathMatcher;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.ext.web.RoutingContext;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static io.undertow.websockets.ServerWebSocketContainer.WebSocketHandshakeHolder;

/**
 * Filter that provides HTTP upgrade functionality. This should be run after all user filters, but before any servlets.
 * <p>
 * The use of a filter rather than a servlet allows for normal HTTP requests to be served from the same location
 * as a web socket endpoint if no upgrade header is found.
 * <p>
 *
 * @author Stuart Douglas
 */
public class VertxWebSocketHandler implements Handler<RoutingContext> {

    private final EndpointSessionHandler callback;
    private final WebsocketPathMatcher<WebSocketHandshakeHolder> pathTemplateMatcher;
    private final ServerWebSocketContainer container;
    private final Executor executor;

    private static final String SESSION_ATTRIBUTE = "io.undertow.websocket.current-connections";


    public VertxWebSocketHandler(ServerWebSocketContainer container, WebSocketDeploymentInfo info) {
        this.container = container;
        this.executor = info.getExecutor().get();
        container.deploymentComplete();
        pathTemplateMatcher = new WebsocketPathMatcher<>();
        for (ConfiguredServerEndpoint endpoint : container.getConfiguredServerEndpoints()) {
            if (info == null || info.getServerExtensions().isEmpty()) {
                pathTemplateMatcher.add(endpoint.getPathTemplate(), container.handshakes(endpoint));
            } else {
                pathTemplateMatcher.add(endpoint.getPathTemplate(), container.handshakes(endpoint, info.getServerExtensions()));
            }
        }
        this.callback = new EndpointSessionHandler(container);
    }

    @Override
    public void handle(RoutingContext event) {
        HttpServerRequest req = event.request();
        HttpServerResponse resp = event.response();
        if (req.getHeader(HttpHeaderNames.UPGRADE) != null) {
            ChannelPipeline pipeline = ((ConnectionBase) event.request().connection()).channel().pipeline();
            final ChannelHandler websocketChannelHandler = pipeline.get("webSocketExtensionHandler");
            if (websocketChannelHandler != null) {
                pipeline.remove(websocketChannelHandler);
            }
            final VertxWebSocketHttpExchange facade = new VertxWebSocketHttpExchange(executor, event);

            String path = event.normalisedPath();
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            WebsocketPathMatcher.PathMatchResult<WebSocketHandshakeHolder> matchResult = pathTemplateMatcher.match(path);
            if (matchResult != null) {
                Handshake handshaker = null;
                for (Handshake method : matchResult.getValue().handshakes) {
                    if (method.matches(facade)) {
                        handshaker = method;
                        break;
                    }
                }

                if (handshaker != null) {
                    if (container.isClosed()) {
                        resp.setStatusCode(HttpResponseStatus.SERVICE_UNAVAILABLE.code()).end();
                        return;
                    }
                    facade.putAttachment(HandshakeUtil.PATH_PARAMS, matchResult.getParameters());
                    //facade.putAttachment(HandshakeUtil.PRINCIPAL, req.getUserPrincipal());
                    final Handshake selected = handshaker;
                    handshaker.handshake(facade, new Consumer<ChannelHandlerContext>() {
                        @Override
                        public void accept(ChannelHandlerContext context) {
                            UndertowSession channel = callback.connected(context, selected.getConfig(), facade, resp.headers().get(io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));

                        }
                    });
                    return;
                }
            }
        }
        event.next();
    }
}
