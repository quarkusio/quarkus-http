package io.undertow.websockets.vertx;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.undertow.websockets.ConfiguredServerEndpoint;
import io.undertow.websockets.DefaultContainerConfigurator;
import io.undertow.websockets.EncodingFactory;
import io.undertow.websockets.EndpointSessionHandler;
import io.undertow.websockets.JsrWebSocketMessages;
import io.undertow.websockets.ServerWebSocketContainer;
import io.undertow.websockets.WebSocketReconnectHandler;
import io.undertow.websockets.annotated.AnnotatedEndpointFactory;
import io.undertow.websockets.handshake.Handshake;
import io.undertow.websockets.handshake.HandshakeUtil;
import io.undertow.websockets.util.ContextSetupHandler;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectHandle;
import io.undertow.websockets.util.ObjectIntrospecter;
import io.undertow.websockets.util.PathTemplate;
import io.vertx.ext.web.RoutingContext;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.server.ServerEndpointConfig;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class VertxServerWebSocketContainer extends ServerWebSocketContainer {


    public VertxServerWebSocketContainer(ObjectIntrospecter objectIntrospecter, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, boolean clientMode) {
        super(objectIntrospecter, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientMode);
    }

    public VertxServerWebSocketContainer(ObjectIntrospecter objectIntrospecter, ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, Supplier<Executor> executorSupplier) {
        super(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, executorSupplier);
    }

    public VertxServerWebSocketContainer(ObjectIntrospecter objectIntrospecter, ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler) {
        super(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientBindAddress, reconnectHandler);
    }

    public VertxServerWebSocketContainer(ObjectIntrospecter objectIntrospecter, ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler, Supplier<Executor> executorSupplier, List<Extension> installedExtensions) {
        super(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientBindAddress, reconnectHandler, executorSupplier, installedExtensions);
    }

    public VertxServerWebSocketContainer(ObjectIntrospecter objectIntrospecter, ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler, Supplier<Executor> executorSupplier, List<Extension> installedExtensions, int maxFrameSize) {
        super(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientBindAddress, reconnectHandler, executorSupplier, installedExtensions, maxFrameSize);
    }


    public void doUpgrade(RoutingContext routingContext, final ServerEndpointConfig sec,
                          Map<String, String> pathParams) {
        ServerEndpointConfig.Configurator configurator = sec.getConfigurator();
        try {
            EncodingFactory encodingFactory = EncodingFactory.createFactory(objectIntrospecter, sec.getDecoders(), sec.getEncoders());
            PathTemplate pt = PathTemplate.create(sec.getPath());

            ObjectFactory<?> ObjectFactory = null;
            try {
                ObjectFactory = objectIntrospecter.createInstanceFactory(sec.getEndpointClass());
            } catch (Exception e) {
                //so it is possible that this is still valid if a custom configurator is in use
                if (configurator == null || configurator.getClass() == ServerEndpointConfig.Configurator.class) {
                    throw JsrWebSocketMessages.MESSAGES.couldNotDeploy(e);
                } else {
                    ObjectFactory = new ObjectFactory<Object>() {
                        @Override
                        public ObjectHandle<Object> createInstance() {
                            throw JsrWebSocketMessages.MESSAGES.endpointDoesNotHaveAppropriateConstructor(sec.getEndpointClass());
                        }
                    };
                }
            }
            if (configurator == null) {
                configurator = DefaultContainerConfigurator.INSTANCE;
            }

            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(sec.getEndpointClass(), sec.getPath())
                    .decoders(sec.getDecoders())
                    .encoders(sec.getEncoders())
                    .subprotocols(sec.getSubprotocols())
                    .extensions(sec.getExtensions())
                    .configurator(configurator)
                    .build();


            AnnotatedEndpointFactory annotatedEndpointFactory = null;
            if (!Endpoint.class.isAssignableFrom(sec.getEndpointClass())) {
                annotatedEndpointFactory = AnnotatedEndpointFactory.create(sec.getEndpointClass(), encodingFactory, pt.getParameterNames(), config);
            }


            ConfiguredServerEndpoint confguredServerEndpoint;
            if (annotatedEndpointFactory == null) {
                confguredServerEndpoint = new ConfiguredServerEndpoint(config, ObjectFactory, null, encodingFactory);
            } else {
                confguredServerEndpoint = new ConfiguredServerEndpoint(config, ObjectFactory, null, encodingFactory, annotatedEndpointFactory, installedExtensions);
            }
            WebSocketHandshakeHolder hand;

            hand = handshakes(confguredServerEndpoint);

            final VertxWebSocketHttpExchange facade = new VertxWebSocketHttpExchange(getExecutorSupplier().get(), routingContext);
            Handshake handshaker = null;
            for (Handshake method : hand.handshakes) {
                if (method.matches(facade)) {
                    handshaker = method;
                    break;
                }
            }

            if (handshaker != null) {
                if (isClosed()) {
                    routingContext.response().setStatusCode(HttpResponseStatus.SERVICE_UNAVAILABLE.code()).end();
                    return;
                }
                facade.putAttachment(HandshakeUtil.PATH_PARAMS, pathParams);
                final Handshake selected = handshaker;
                handshaker.handshake(facade, new Consumer<ChannelHandlerContext>() {
                    @Override
                    public void accept(ChannelHandlerContext context) {
                        new EndpointSessionHandler(VertxServerWebSocketContainer.this).connected(context, confguredServerEndpoint, facade, null);
                    }
                });
            }
        } catch (Exception e) {
            routingContext.fail(e);
        }
    }
}
