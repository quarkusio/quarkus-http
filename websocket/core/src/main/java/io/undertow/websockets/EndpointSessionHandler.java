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
package io.undertow.websockets;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.undertow.websockets.annotated.AnnotatedEndpoint;
import io.undertow.websockets.handshake.HandshakeUtil;
import io.undertow.websockets.handshake.WebSocketHttpExchange;
import io.undertow.websockets.util.ImmediateObjectHandle;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectHandle;

import jakarta.websocket.Endpoint;
import jakarta.websocket.Extension;
import jakarta.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;

/**
 * implementation which will setuo the {@link UndertowSession} and notify
 * the {@link Endpoint} about the new session.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public final class EndpointSessionHandler {
    private final ServerWebSocketContainer container;

    public EndpointSessionHandler(ServerWebSocketContainer container) {
        this.container = container;
    }

    /**
     * Returns the {@link ServerWebSocketContainer} which was used for this
     */
    ServerWebSocketContainer getContainer() {
        return container;
    }

    public UndertowSession connected(ChannelHandlerContext context, ConfiguredServerEndpoint config, WebSocketHttpExchange exchange, String subprotocol) {
        try {
            if (container.isClosed()) {
                //if the underlying container is closed we just reject
                context.write(new CloseWebSocketFrame());
                return null;
            }
            ObjectFactory<?> endpointFactory = config.getEndpointFactory();
            ServerEndpointConfig.Configurator configurator = config.getEndpointConfiguration().getConfigurator();
            final ObjectHandle<?> instance;
            DefaultContainerConfigurator.setCurrentInstanceFactory(endpointFactory);
            final Object instanceFromConfigurator = configurator.getEndpointInstance(config.getEndpointConfiguration().getEndpointClass());
            final ObjectHandle<?> factoryInstance = DefaultContainerConfigurator.clearCurrentInstanceFactory();
            if (factoryInstance == null) {
                instance = new ImmediateObjectHandle<>(instanceFromConfigurator);
            } else if (factoryInstance.getInstance() == instanceFromConfigurator) {
                instance = factoryInstance;
            } else {
                //the default instance has been wrapped
                instance = new ObjectHandle<Object>() {
                    @Override
                    public Object getInstance() {
                        return instanceFromConfigurator;
                    }

                    @Override
                    public void release() {
                        factoryInstance.release();
                    }
                };
            }

            Principal principal = exchange.getUserPrincipal();
            final ObjectHandle<Endpoint> endpointInstance;
            if (config.getAnnotatedEndpointFactory() != null) {
                final AnnotatedEndpoint annotated = config.getAnnotatedEndpointFactory().createInstance(instance);
                endpointInstance = new ObjectHandle<Endpoint>() {
                    @Override
                    public Endpoint getInstance() {
                        return annotated;
                    }

                    @Override
                    public void release() {
                        instance.release();
                    }
                };
            } else {
                endpointInstance = (ObjectHandle<Endpoint>) instance;
            }

            UndertowSession session = new UndertowSession(context.channel(), URI.create(exchange.getRequestURI()),
                    exchange.getAttachment(HandshakeUtil.PATH_PARAMS), exchange.getRequestParameters(),
                    this, principal, endpointInstance, config.getEndpointConfiguration(), exchange.getQueryString(),
                    config.getEncodingFactory().createEncoding(config.getEndpointConfiguration()), config, subprotocol,
                    Collections.<Extension>emptyList(), null, exchange.getExecutor());
            config.addOpenSession(session);

            session.setMaxBinaryMessageBufferSize(getContainer().getDefaultMaxBinaryMessageBufferSize());
            session.setMaxTextMessageBufferSize(getContainer().getDefaultMaxTextMessageBufferSize());
            session.setMaxIdleTimeout(getContainer().getDefaultMaxSessionIdleTimeout());
            session.getAsyncRemote().setSendTimeout(getContainer().getDefaultAsyncSendTimeout());
            try {
                endpointInstance.getInstance().onOpen(session, config.getEndpointConfiguration());
                session.getFrameHandler().start();
            } catch (Exception e) {
                endpointInstance.getInstance().onError(session, e);
                session.close();
            } finally {
                session.getChannel().config().setAutoRead(true);
                session.getChannel().read();
            }
            return session;
        } catch (Exception e) {
            JsrWebSocketLogger.REQUEST_LOGGER.endpointCreationFailed(e);
            context.close();
            return null;
        }
    }
}
