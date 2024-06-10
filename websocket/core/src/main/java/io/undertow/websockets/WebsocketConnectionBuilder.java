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

package io.undertow.websockets;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.CharsetUtil;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class WebsocketConnectionBuilder {
    private final URI uri;
    private final EventLoopGroup eventLoopGroup;

    private SSLContext ssl;
    private InetSocketAddress bindAddress;
    private WebSocketVersion version = WebSocketVersion.V13;
    private WebSocketClientNegotiation clientNegotiation;
    //    private Set<WebSocketExtensionHandshake> clientExtensions;
    private URI proxyUri;
    private SslContext proxySsl;
    private final int maxFrameSize;

    public WebsocketConnectionBuilder(URI uri, EventLoopGroup eventLoopGroup, int maxFrameSize) {
        this.uri = uri;
        this.eventLoopGroup = eventLoopGroup;
        this.maxFrameSize = maxFrameSize;
    }


    public URI getUri() {
        return uri;
    }

    public SSLContext getSsl() {
        return ssl;
    }

    public WebsocketConnectionBuilder setSsl(SSLContext ssl) {
        this.ssl = ssl;
        return this;
    }

    public InetSocketAddress getBindAddress() {
        return bindAddress;
    }

    public WebsocketConnectionBuilder setBindAddress(InetSocketAddress bindAddress) {
        this.bindAddress = bindAddress;
        return this;
    }

    public WebSocketVersion getVersion() {
        return version;
    }

    public WebsocketConnectionBuilder setVersion(WebSocketVersion version) {
        this.version = version;
        return this;
    }

    public WebSocketClientNegotiation getClientNegotiation() {
        return clientNegotiation;
    }

    public WebsocketConnectionBuilder setClientNegotiation(WebSocketClientNegotiation clientNegotiation) {
        this.clientNegotiation = clientNegotiation;
        return this;
    }

    public URI getProxyUri() {
        return proxyUri;
    }

    public WebsocketConnectionBuilder setProxyUri(URI proxyUri) {
        this.proxyUri = proxyUri;
        return this;
    }

    public SslContext getProxySsl() {
        return proxySsl;
    }

    public WebsocketConnectionBuilder setProxySsl(SslContext proxySsl) {
        this.proxySsl = proxySsl;
        return this;
    }

    public <R> CompletableFuture<R> connect(Function<Channel, R> connectFunction) {
        io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
        int actualPort = uri.getPort() == -1 ? (uri.getScheme().equals("wss") ? 443 : 80) : uri.getPort();

        final WebSocketClientHandler handler =
                new WebSocketClientHandler(
                        new WebSocketClientHandshaker13(
                                uri, WebSocketVersion.V13, null, !clientNegotiation.getSupportedExtensions().isEmpty(), HttpHeaders.EMPTY_HEADERS, maxFrameSize) {

                            @Override
                            protected FullHttpRequest newHandshakeRequest() {
                                FullHttpRequest request = super.newHandshakeRequest();
                                if (clientNegotiation.getSupportedSubProtocols() != null) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < clientNegotiation.getSupportedSubProtocols().size(); ++i) {
                                        if (i > 0) {
                                            sb.append(", ");
                                        }
                                        sb.append(clientNegotiation.getSupportedSubProtocols().get(i));
                                    }
                                    if (sb.length() > 0) {
                                        request.headers().add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, sb.toString());
                                    }
                                }
                                if (clientNegotiation.getSupportedExtensions() != null) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < clientNegotiation.getSupportedExtensions().size(); ++i) {
                                        if (i > 0) {
                                            sb.append(", ");
                                        }
                                        WebSocketExtensionData data = clientNegotiation.getSupportedExtensions().get(i);
                                        sb.append(data.name());
                                        for (Map.Entry<String, String> parameter : data.parameters().entrySet()) {
                                            sb.append(";");
                                            sb.append(parameter.getKey());
                                            if (parameter.getValue() != null) {
                                                sb.append("=");
                                                sb.append(parameter.getValue());
                                            }
                                        }
                                    }
                                    if (sb.length() > 0) {
                                        request.headers().add(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, sb.toString());
                                    }
                                }
                                clientNegotiation.beforeRequest(request.headers());
                                return request;
                            }

                            @Override
                            protected void verify(FullHttpResponse response) {
                                super.verify(response);
                                clientNegotiation.afterRequest(response.headers());
                            }
                        }, connectFunction);


        b.group(determineEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (ssl != null) {
                            SSLEngine sslEngine = ssl.createSSLEngine(uri.getHost(), actualPort);
                            sslEngine.setUseClientMode(true);
                            pipeline.addLast("ssl", new SslHandler(sslEngine));
                        }
                        pipeline.addLast("http-codec", new HttpClientCodec());
                        pipeline.addLast("extensions-handler", WebSocketClientCompressionHandler.INSTANCE);
                        pipeline.addLast("aggregator", new HttpObjectAggregator(65536));
                        pipeline.addLast("ws-handler", handler);
                    }
                });

        //System.out.println("WebSocket Client connecting");
        b.connect(uri.getHost(), actualPort).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.cause() != null) {
                    handler.handshakeFuture.completeExceptionally(future.cause());
                }
            }
        });


        return handler.handshakeFuture;
    }

    // the bootstrap always uses NioSocketChannel, so we need to provide a proper NioEventLoopGroup
    private EventLoopGroup determineEventLoopGroup() {
        if (NioEventLoopGroup.class.equals(eventLoopGroup.getClass())) {
            return eventLoopGroup;
        }
        // there is not much else we can do here...
        return new NioEventLoopGroup();
    }


    private class WebSocketClientHandler<R> extends SimpleChannelInboundHandler<Object> {

        private final WebSocketClientHandshaker handshaker;
        private final CompletableFuture<R> handshakeFuture = new CompletableFuture<>();
        private final Function<Channel, R> connectFunction;

        public WebSocketClientHandler(WebSocketClientHandshaker handshaker, Function<Channel, R> connectFunction) {
            this.handshaker = handshaker;
            this.connectFunction = connectFunction;
        }

        public CompletableFuture<R> handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            handshakeFuture.completeExceptionally(new ClosedChannelException());
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                    handshakeFuture.complete(connectFunction.apply(ch));
                    ch.pipeline().remove(this);

                } catch (Exception e) {
                    handshakeFuture.completeExceptionally(e);
                }
                return;
            }

            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                throw new IllegalStateException(
                        "Unexpected FullHttpResponse (getStatus=" + response.status() +
                                ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!handshakeFuture.isDone()) {
                handshakeFuture.completeExceptionally(cause);
            }
            ctx.close();
        }
    }

}
