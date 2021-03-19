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

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandshaker;
import io.undertow.websockets.annotated.AnnotatedEndpointFactory;
import io.undertow.websockets.handshake.Handshake;
import io.undertow.websockets.util.ObjectIntrospecter;
import io.undertow.websockets.util.ConstructorObjectFactory;
import io.undertow.websockets.util.ImmediateObjectHandle;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectHandle;
import io.undertow.websockets.util.PathTemplate;
import io.undertow.websockets.util.ContextSetupHandler;

import javax.net.ssl.SSLContext;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.System.currentTimeMillis;


/**
 * {@link ServerContainer} implementation which allows to deploy endpoints for a server.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public class ServerWebSocketContainer implements ServerContainer, Closeable {

    public static final String TIMEOUT = "io.undertow.websocket.CONNECT_TIMEOUT";
    public static final int DEFAULT_WEB_SOCKET_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_MAX_FRAME_SIZE = 65536;

    protected final ObjectIntrospecter objectIntrospecter;

    private final Map<Class<?>, ConfiguredClientEndpoint> clientEndpoints = new ConcurrentHashMap<>();

    private final List<ConfiguredServerEndpoint> configuredServerEndpoints = new ArrayList<>();
    private final Set<Class<?>> annotatedEndpointClasses = new HashSet<>();

    /**
     * set of all deployed server endpoint paths. Due to the comparison function we can detect
     * overlaps
     */
    private final TreeSet<PathTemplate> seenPaths = new TreeSet<>();

    private final boolean dispatchToWorker;
    private final InetSocketAddress clientBindAddress;
    private final WebSocketReconnectHandler webSocketReconnectHandler;
    private final Supplier<EventLoopGroup> eventLoopSupplier;
    private final Supplier<Executor> executorSupplier;

    private volatile long defaultAsyncSendTimeout;
    private volatile long defaultMaxSessionIdleTimeout;
    private volatile int defaultMaxBinaryMessageBufferSize;
    private volatile int defaultMaxTextMessageBufferSize;
    private volatile boolean deploymentComplete = false;
    private final List<DeploymentException> deploymentExceptions = new ArrayList<>();

    private final List<PauseListener> pauseListeners = new ArrayList<>();
    protected final List<Extension> installedExtensions;
    private final List<WebsocketClientSslProvider> clientSslProviders;
    private final int maxFrameSize;

    private final ContextSetupHandler.Action<Void, Runnable> invokeEndpointTask;

    private volatile boolean closed = false;

    public ServerWebSocketContainer(final ObjectIntrospecter objectIntrospecter, final Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, boolean clientMode) {
        this(objectIntrospecter, ServerWebSocketContainer.class.getClassLoader(), eventLoopSupplier, contextSetupHandlers, dispatchToWorker, null, null);
    }

    public ServerWebSocketContainer(final ObjectIntrospecter objectIntrospecter, final ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, Supplier<Executor> executorSupplier) {
        this(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, null, null, executorSupplier, Collections.emptyList());
    }

    public ServerWebSocketContainer(final ObjectIntrospecter objectIntrospecter, final ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler) {
        this(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientBindAddress, reconnectHandler, null, Collections.emptyList());
    }

    public ServerWebSocketContainer(final ObjectIntrospecter objectIntrospecter, final ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler, Supplier<Executor> executorSupplier, List<Extension> installedExtensions) {
        this(objectIntrospecter, classLoader, eventLoopSupplier, contextSetupHandlers, dispatchToWorker, clientBindAddress, reconnectHandler, executorSupplier, installedExtensions, DEFAULT_MAX_FRAME_SIZE);
    }

    public ServerWebSocketContainer(final ObjectIntrospecter objectIntrospecter, final ClassLoader classLoader, Supplier<EventLoopGroup> eventLoopSupplier, List<ContextSetupHandler> contextSetupHandlers, boolean dispatchToWorker, InetSocketAddress clientBindAddress, WebSocketReconnectHandler reconnectHandler, Supplier<Executor> executorSupplier, List<Extension> installedExtensions, int maxFrameSize) {
        this.objectIntrospecter = objectIntrospecter;
        this.eventLoopSupplier = eventLoopSupplier;
        this.dispatchToWorker = dispatchToWorker;
        this.clientBindAddress = clientBindAddress;
        this.executorSupplier = executorSupplier;
        this.installedExtensions = new ArrayList<>(installedExtensions);
        this.webSocketReconnectHandler = reconnectHandler;
        this.maxFrameSize = maxFrameSize;
        ContextSetupHandler.Action<Void, Runnable> task = new ContextSetupHandler.Action<Void, Runnable>() {
            @Override
            public Void call(Runnable context) throws Exception {
                context.run();
                return null;
            }
        };
        List<WebsocketClientSslProvider> clientSslProviders = new ArrayList<>();
        for (WebsocketClientSslProvider provider : ServiceLoader.load(WebsocketClientSslProvider.class, classLoader)) {
            clientSslProviders.add(provider);
        }

        this.clientSslProviders = Collections.unmodifiableList(clientSslProviders);
        for (ContextSetupHandler handler : contextSetupHandlers) {
            task = handler.create(task);
        }
        this.invokeEndpointTask = task;
    }

    @Override
    public long getDefaultAsyncSendTimeout() {
        return defaultAsyncSendTimeout;
    }

    @Override
    public void setAsyncSendTimeout(long defaultAsyncSendTimeout) {
        this.defaultAsyncSendTimeout = defaultAsyncSendTimeout;
    }

    protected Supplier<Executor> getExecutorSupplier() {
        return executorSupplier;
    }

    public Session connectToServer(final Object annotatedEndpointInstance, WebsocketConnectionBuilder connectionBuilder) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ConfiguredClientEndpoint config = getClientEndpoint(annotatedEndpointInstance.getClass(), false);
        if (config == null) {
            throw JsrWebSocketMessages.MESSAGES.notAValidClientEndpointType(annotatedEndpointInstance.getClass());
        }
        Endpoint instance = config.getFactory().createInstance(new ImmediateObjectHandle<>(annotatedEndpointInstance));
        return connectToServerInternal(instance, config, connectionBuilder);
    }

    @Override
    public Session connectToServer(final Object annotatedEndpointInstance, final URI path) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ConfiguredClientEndpoint config = getClientEndpoint(annotatedEndpointInstance.getClass(), false);
        if (config == null) {
            throw JsrWebSocketMessages.MESSAGES.notAValidClientEndpointType(annotatedEndpointInstance.getClass());
        }
        Endpoint instance = config.getFactory().createInstance(new ImmediateObjectHandle<>(annotatedEndpointInstance));
        SSLContext ssl = null;

        if (path.getScheme().equals("wss")) {
            for (WebsocketClientSslProvider provider : clientSslProviders) {
                ssl = provider.getSsl(eventLoopSupplier.get(), annotatedEndpointInstance, path);
                if (ssl != null) {
                    break;
                }
            }
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    //ignore
                }
            }
        }
        return connectToServerInternal(instance, ssl, config, path);
    }

    public Session connectToServer(Class<?> aClass, WebsocketConnectionBuilder connectionBuilder) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ConfiguredClientEndpoint config = getClientEndpoint(aClass, true);
        if (config == null) {
            throw JsrWebSocketMessages.MESSAGES.notAValidClientEndpointType(aClass);
        }
        AnnotatedEndpointFactory factory = config.getFactory();
        ObjectHandle<?> instance = config.getInstanceFactory().createInstance();
        return connectToServerInternal(factory.createInstance(instance), config, connectionBuilder);
    }

    @Override
    public Session connectToServer(Class<?> aClass, URI uri) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ConfiguredClientEndpoint config = getClientEndpoint(aClass, true);
        if (config == null) {
            throw JsrWebSocketMessages.MESSAGES.notAValidClientEndpointType(aClass);
        }
        AnnotatedEndpointFactory factory = config.getFactory();
        ObjectHandle<?> instance = config.getInstanceFactory().createInstance();
        SSLContext ssl = null;
        if (uri.getScheme().equals("wss")) {
            for (WebsocketClientSslProvider provider : clientSslProviders) {
                ssl = provider.getSsl(eventLoopSupplier.get(), aClass, uri);
                if (ssl != null) {
                    break;
                }
            }
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    //ignore
                }
            }
        }
        return connectToServerInternal(factory.createInstance(instance), ssl, config, uri);
    }

    @Override
    public Session connectToServer(final Endpoint endpointInstance, final ClientEndpointConfig config, final URI path) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ClientEndpointConfig cec = config != null ? config : ClientEndpointConfig.Builder.create().build();

        SSLContext ssl = null;
        if (path.getScheme().equals("wss")) {
            for (WebsocketClientSslProvider provider : clientSslProviders) {
                ssl = provider.getSsl(eventLoopSupplier.get(), endpointInstance, cec, path);
                if (ssl != null) {
                    break;
                }
            }
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    //ignore
                }
            }
        }
        //in theory we should not be able to connect until the deployment is complete, but the definition of when a deployment is complete is a bit nebulous.
        ClientNegotiation clientNegotiation = new ClientNegotiation(cec.getPreferredSubprotocols(), toExtensionList(cec.getExtensions()), cec);


        WebsocketConnectionBuilder connectionBuilder = new WebsocketConnectionBuilder(path, eventLoopSupplier.get())
                .setSsl(ssl)
                .setBindAddress(clientBindAddress)
                .setClientNegotiation(clientNegotiation);

        return connectToServer(endpointInstance, config, connectionBuilder);
    }

    private static List<WebSocketExtensionData> toExtensionList(final List<Extension> extensions) {
        List<WebSocketExtensionData> ret = new ArrayList<>();
        for (Extension e : extensions) {
            final Map<String, String> parameters = new HashMap<>();
            for (Extension.Parameter p : e.getParameters()) {
                parameters.put(p.getName(), p.getValue());
            }
            ret.add(new WebSocketExtensionData(e.getName(), parameters));
        }
        return ret;
    }

    public Session connectToServer(final Endpoint endpointInstance, final ClientEndpointConfig config, WebsocketConnectionBuilder connectionBuilder) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        ClientEndpointConfig cec = config != null ? config : ClientEndpointConfig.Builder.create().build();

        WebSocketClientNegotiation clientNegotiation = connectionBuilder.getClientNegotiation();

        CompletableFuture<UndertowSession> sessionCompletableFuture = new CompletableFuture<>();

        EndpointSessionHandler sessionHandler = new EndpointSessionHandler(this);

        final List<Extension> extensions = new ArrayList<>();
        final Map<String, Extension> extMap = new HashMap<>();
        for (Extension ext : cec.getExtensions()) {
            extMap.put(ext.getName(), ext);
        }
        for (WebSocketExtensionData e : clientNegotiation.getSelectedExtensions()) {
            Extension ext = extMap.get(e.name());
            if (ext == null) {
                throw JsrWebSocketMessages.MESSAGES.extensionWasNotPresentInClientHandshake(e.name(), clientNegotiation.getSupportedExtensions());
            }
            extensions.add(new ExtensionImpl(e));
        }
        CompletableFuture<UndertowSession> session = connectionBuilder
                .connect(new Function<Channel, UndertowSession>() {
                    @Override
                    public UndertowSession apply(Channel channel) {
                        channel.config().setAutoRead(false);

                        ConfiguredClientEndpoint configured = clientEndpoints.get(endpointInstance.getClass());
                        if (configured == null) {
                            synchronized (clientEndpoints) {
                                configured = clientEndpoints.get(endpointInstance.getClass());
                                if (configured == null) {
                                    clientEndpoints.put(endpointInstance.getClass(), configured = new ConfiguredClientEndpoint());
                                }
                            }
                        }

                        EncodingFactory encodingFactory = null;
                        try {
                            encodingFactory = EncodingFactory.createFactory(objectIntrospecter, cec.getDecoders(), cec.getEncoders());
                        } catch (DeploymentException e) {
                            throw new RuntimeException(e);
                        }
                        UndertowSession undertowSession = new UndertowSession(channel, connectionBuilder.getUri(), Collections.<String, String>emptyMap(), Collections.<String, List<String>>emptyMap(), sessionHandler, null, new ImmediateObjectHandle<>(endpointInstance), cec, connectionBuilder.getUri().getQuery(), encodingFactory.createEncoding(cec), configured, clientNegotiation.getSelectedSubProtocol(), extensions, connectionBuilder, executorSupplier.get());
                        invokeEndpointMethod(executorSupplier.get(), new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    endpointInstance.onOpen(undertowSession, cec);
                                } finally {
                                    undertowSession.getFrameHandler().start();
                                    channel.config().setAutoRead(true);
                                    channel.read();
                                    sessionCompletableFuture.complete(undertowSession);
                                }
                            }
                        });
                        return undertowSession;
                    }
                }).exceptionally(new Function<Throwable, UndertowSession>() {
                    @Override
                    public UndertowSession apply(Throwable throwable) {
                        sessionCompletableFuture.completeExceptionally(throwable);
                        return null;
                    }
                });
        Number timeout = (Number) cec.getUserProperties().get(TIMEOUT);


        try {
            return sessionCompletableFuture.get(timeout == null ? DEFAULT_WEB_SOCKET_TIMEOUT_SECONDS : timeout.intValue(), TimeUnit.SECONDS);
        } catch (Exception e) {
            session.cancel(true);
            throw new IOException(e);
        }
    }


    @Override
    public Session connectToServer(final Class<? extends Endpoint> endpointClass, final ClientEndpointConfig cec, final URI path) throws DeploymentException, IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        Endpoint endpoint = (Endpoint) objectIntrospecter.createInstanceFactory(endpointClass).createInstance().getInstance();
        return connectToServer(endpoint, cec, path);
    }


    private Session connectToServerInternal(final Endpoint endpointInstance, SSLContext ssl, final ConfiguredClientEndpoint cec, final URI path) throws DeploymentException, IOException {
        //in theory we should not be able to connect until the deployment is complete, but the definition of when a deployment is complete is a bit nebulous.
        ClientNegotiation clientNegotiation = new ClientNegotiation(cec.getConfig().getPreferredSubprotocols(), toExtensionList(cec.getConfig().getExtensions()), cec.getConfig());


        WebsocketConnectionBuilder connectionBuilder = new WebsocketConnectionBuilder(path, eventLoopSupplier.get())
                .setSsl(ssl)
                .setBindAddress(clientBindAddress)
                .setClientNegotiation(clientNegotiation);
        return connectToServerInternal(endpointInstance, cec, connectionBuilder);
    }

    private Session connectToServerInternal(final Endpoint endpointInstance, final ConfiguredClientEndpoint cec, WebsocketConnectionBuilder connectionBuilder) throws DeploymentException, IOException {

        final List<Extension> extensions = new ArrayList<>();
        final Map<String, Extension> extMap = new HashMap<>();
        for (Extension ext : cec.getConfig().getExtensions()) {
            extMap.put(ext.getName(), ext);
        }
        String subProtocol = null;
        if (connectionBuilder.getClientNegotiation() != null) {
            for (WebSocketExtensionData e : connectionBuilder.getClientNegotiation().getSelectedExtensions()) {
                Extension ext = extMap.get(e.name());
                if (ext == null) {
                    throw JsrWebSocketMessages.MESSAGES.extensionWasNotPresentInClientHandshake(e.name(), connectionBuilder.getClientNegotiation().getSupportedExtensions());
                }
                extensions.add(new ExtensionImpl(e));
            }
            subProtocol = connectionBuilder.getClientNegotiation().getSelectedSubProtocol();
        }
        String finalSubProtocol = subProtocol;
        EndpointSessionHandler sessionHandler = new EndpointSessionHandler(this);
        CompletableFuture<UndertowSession> session = connectionBuilder.connect(new Function<Channel, UndertowSession>() {
            @Override
            public UndertowSession apply(Channel channel) {
                return new UndertowSession(channel, connectionBuilder.getUri(), Collections.<String, String>emptyMap(), Collections.<String, List<String>>emptyMap(), sessionHandler, null, new ImmediateObjectHandle<>(endpointInstance), cec.getConfig(), connectionBuilder.getUri().getQuery(), cec.getEncodingFactory().createEncoding(cec.getConfig()), cec, finalSubProtocol, extensions, connectionBuilder, executorSupplier.get());

            }
        });
        Number timeout = (Number) cec.getConfig().getUserProperties().get(TIMEOUT);
        try {
            UndertowSession result = session.get(timeout == null ? DEFAULT_WEB_SOCKET_TIMEOUT_SECONDS : timeout.intValue(), TimeUnit.SECONDS);

            invokeEndpointMethod(executorSupplier.get(), new Runnable() {
                @Override
                public void run() {
                    try {
                        endpointInstance.onOpen(result, cec.getConfig());
                    } finally {
                        result.getFrameHandler().start();
                        result.getChannel().config().setAutoRead(true);
                        result.getChannel().read();
                    }
                }
            });
            return result;
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            InterruptedIOException interruptedIOException = new InterruptedIOException();
            interruptedIOException.addSuppressed(e);
            throw interruptedIOException;
        }
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout() {
        return defaultMaxSessionIdleTimeout;
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(final long timeout) {
        this.defaultMaxSessionIdleTimeout = timeout;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize() {
        return defaultMaxBinaryMessageBufferSize;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int defaultMaxBinaryMessageBufferSize) {
        this.defaultMaxBinaryMessageBufferSize = defaultMaxBinaryMessageBufferSize;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize() {
        return defaultMaxTextMessageBufferSize;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int defaultMaxTextMessageBufferSize) {
        this.defaultMaxTextMessageBufferSize = defaultMaxTextMessageBufferSize;
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        return new HashSet<>(installedExtensions);
    }

    /**
     * Runs a web socket invocation, setting up the threads and dispatching a thread pool
     * <p>
     * Unfortunately we need to dispatch to a thread pool, because there is a good chance that the endpoint
     * will use blocking IO methods. We suspend recieves while this is in progress, to make sure that we do not have multiple
     * methods invoked at once.
     * <p>
     *
     * @param invocation The task to run
     */
    public void invokeEndpointMethod(final Executor executor, final Runnable invocation) {
        if (dispatchToWorker) {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        invokeEndpointMethod(invocation);
                    }
                });
            } catch (RejectedExecutionException e) {
                invokeEndpointMethod(invocation);
            }
        } else {
            invokeEndpointMethod(invocation);
        }
    }

    /**
     * Directly invokes an endpoint method, without dispatching to an executor
     *
     * @param invocation The invocation
     */
    public void invokeEndpointMethod(final Runnable invocation) {
        try {
            invokeEndpointTask.call(invocation);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addEndpoint(final Class<?> endpoint) throws DeploymentException {
        if (deploymentComplete) {
            throw JsrWebSocketMessages.MESSAGES.cannotAddEndpointAfterDeployment();
        }
        //work around a TCK7 problem
        //if the class has already been added we just ignore it
        if (annotatedEndpointClasses.contains(endpoint)) {
            return;
        }
        annotatedEndpointClasses.add(endpoint);
        try {
            addEndpointInternal(endpoint, true);
        } catch (DeploymentException e) {
            deploymentExceptions.add(e);
            throw e;
        }
    }

    private synchronized void addEndpointInternal(final Class<?> endpoint, boolean requiresCreation) throws DeploymentException {
        ServerEndpoint serverEndpoint = endpoint.getAnnotation(ServerEndpoint.class);
        ClientEndpoint clientEndpoint = endpoint.getAnnotation(ClientEndpoint.class);
        if (serverEndpoint != null) {
            JsrWebSocketLogger.ROOT_LOGGER.addingAnnotatedServerEndpoint(endpoint, serverEndpoint.value());
            final PathTemplate template = PathTemplate.create(serverEndpoint.value());
            if (seenPaths.contains(template)) {
                PathTemplate existing = null;
                for (PathTemplate p : seenPaths) {
                    if (p.compareTo(template) == 0) {
                        existing = p;
                        break;
                    }
                }
                throw JsrWebSocketMessages.MESSAGES.multipleEndpointsWithOverlappingPaths(template, existing);
            }
            seenPaths.add(template);
            Class<? extends ServerEndpointConfig.Configurator> configuratorClass = serverEndpoint.configurator();

            EncodingFactory encodingFactory = EncodingFactory.createFactory(objectIntrospecter, serverEndpoint.decoders(), serverEndpoint.encoders());
            AnnotatedEndpointFactory annotatedEndpointFactory = AnnotatedEndpointFactory.create(endpoint, encodingFactory, template.getParameterNames());
            ObjectFactory<?> ObjectFactory = null;
            try {
                ObjectFactory = objectIntrospecter.createInstanceFactory(endpoint);
            } catch (Exception e) {
                //so it is possible that this is still valid if a custom configurator is in use
                if (configuratorClass == ServerEndpointConfig.Configurator.class) {
                    throw JsrWebSocketMessages.MESSAGES.couldNotDeploy(e);
                } else {
                    ObjectFactory = new ObjectFactory<Object>() {
                        @Override
                        public ObjectHandle<Object> createInstance() {
                            throw JsrWebSocketMessages.MESSAGES.endpointDoesNotHaveAppropriateConstructor(endpoint);
                        }
                    };
                }
            }
            ServerEndpointConfig.Configurator configurator;
            if (configuratorClass != ServerEndpointConfig.Configurator.class) {
                configurator = (ServerEndpointConfig.Configurator) objectIntrospecter.createInstanceFactory(configuratorClass).createInstance().getInstance();
            } else {
                configurator = DefaultContainerConfigurator.INSTANCE;
            }

            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(endpoint, serverEndpoint.value())
                    .decoders(Arrays.asList(serverEndpoint.decoders()))
                    .encoders(Arrays.asList(serverEndpoint.encoders()))
                    .subprotocols(Arrays.asList(serverEndpoint.subprotocols()))
                    .extensions(Collections.emptyList())
                    .configurator(configurator)
                    .build();

            EncodingFactory encodingFactory = EncodingFactory.createFactory(classIntrospecter, serverEndpoint.decoders(), serverEndpoint.encoders());
            AnnotatedEndpointFactory annotatedEndpointFactory = AnnotatedEndpointFactory.create(endpoint, encodingFactory, template.getParameterNames(), config);
            InstanceFactory<?> instanceFactory = null;
            try {
                instanceFactory = classIntrospecter.createInstanceFactory(endpoint);
            } catch (Exception e) {
                //so it is possible that this is still valid if a custom configurator is in use
                if (configuratorClass == ServerEndpointConfig.Configurator.class) {
                    throw JsrWebSocketMessages.MESSAGES.couldNotDeploy(e);
                } else {
                    instanceFactory = new InstanceFactory<Object>() {
                        @Override
                        public InstanceHandle<Object> createInstance() throws InstantiationException {
                            throw JsrWebSocketMessages.MESSAGES.endpointDoesNotHaveAppropriateConstructor(endpoint);
                        }
                    };
                }
            }

            ConfiguredServerEndpoint confguredServerEndpoint = new ConfiguredServerEndpoint(config, ObjectFactory, template, encodingFactory, annotatedEndpointFactory, installedExtensions);
            configuredServerEndpoints.add(confguredServerEndpoint);
            handleAddingFilterMapping();
        } else if (clientEndpoint != null) {
            JsrWebSocketLogger.ROOT_LOGGER.addingAnnotatedClientEndpoint(endpoint);
            EncodingFactory encodingFactory = EncodingFactory.createFactory(objectIntrospecter, clientEndpoint.decoders(), clientEndpoint.encoders());
            ObjectFactory<?> ObjectFactory;
            try {
                ObjectFactory = objectIntrospecter.createInstanceFactory(endpoint);
            } catch (Exception e) {
                try {
                    ObjectFactory = new ConstructorObjectFactory<>(endpoint.getConstructor()); //this endpoint cannot be created by the container, the user will instantiate it
                } catch (NoSuchMethodException e1) {
                    if (requiresCreation) {
                        throw JsrWebSocketMessages.MESSAGES.couldNotDeploy(e);
                    } else {
                        ObjectFactory = new ObjectFactory<Object>() {
                            @Override
                            public ObjectHandle<Object> createInstance() {
                                throw new RuntimeException();
                            }
                        };
                    }
                }
            }

            ClientEndpointConfig.Configurator configurator = null;
            configurator = objectIntrospecter.createInstanceFactory(clientEndpoint.configurator()).createInstance().getInstance();

            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .decoders(Arrays.asList(clientEndpoint.decoders()))
                    .encoders(Arrays.asList(clientEndpoint.encoders()))
                    .preferredSubprotocols(Arrays.asList(clientEndpoint.subprotocols()))
                    .configurator(configurator)
                    .build();

            AnnotatedEndpointFactory factory = AnnotatedEndpointFactory.create(endpoint, encodingFactory, Collections.emptySet(), config);

            ConfiguredClientEndpoint configuredClientEndpoint = new ConfiguredClientEndpoint(config, factory, encodingFactory, ObjectFactory);
            clientEndpoints.put(endpoint, configuredClientEndpoint);
        } else {
            throw JsrWebSocketMessages.MESSAGES.classWasNotAnnotated(endpoint);
        }
    }


    protected void handleAddingFilterMapping() {
    }

    @Override
    public void addEndpoint(final ServerEndpointConfig endpoint) throws DeploymentException {
        if (deploymentComplete) {
            throw JsrWebSocketMessages.MESSAGES.cannotAddEndpointAfterDeployment();
        }
        JsrWebSocketLogger.ROOT_LOGGER.addingProgramaticEndpoint(endpoint.getEndpointClass(), endpoint.getPath());
        final PathTemplate template = PathTemplate.create(endpoint.getPath());
        if (seenPaths.contains(template)) {
            PathTemplate existing = null;
            for (PathTemplate p : seenPaths) {
                if (p.compareTo(template) == 0) {
                    existing = p;
                    break;
                }
            }
            throw JsrWebSocketMessages.MESSAGES.multipleEndpointsWithOverlappingPaths(template, existing);
        }
        seenPaths.add(template);
        EncodingFactory encodingFactory = EncodingFactory.createFactory(objectIntrospecter, endpoint.getDecoders(), endpoint.getEncoders());

        AnnotatedEndpointFactory annotatedEndpointFactory = null;
        if (!Endpoint.class.isAssignableFrom(endpoint.getEndpointClass())) {
            // We may want to check that the path in @ServerEndpoint matches the specified path, and throw if they are not equivalent
            annotatedEndpointFactory = AnnotatedEndpointFactory.create(endpoint.getEndpointClass(), encodingFactory, template.getParameterNames(), endpoint);
        }
        ConfiguredServerEndpoint confguredServerEndpoint = new ConfiguredServerEndpoint(endpoint, null, template, encodingFactory, annotatedEndpointFactory, endpoint.getExtensions());
        configuredServerEndpoints.add(confguredServerEndpoint);
        handleAddingFilterMapping();
    }


    private ConfiguredClientEndpoint getClientEndpoint(final Class<?> endpointType, boolean requiresCreation) {
        Class<?> type = endpointType;
        while (type != Object.class && type != null && !type.isAnnotationPresent(ClientEndpoint.class)) {
            type = type.getSuperclass();
        }
        if (type == Object.class || type == null) {
            return null;
        }

        ConfiguredClientEndpoint existing = clientEndpoints.get(type);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = clientEndpoints.get(type);
            if (existing != null) {
                return existing;
            }
            if (type.isAnnotationPresent(ClientEndpoint.class)) {
                try {
                    addEndpointInternal(type, requiresCreation);
                    return clientEndpoints.get(type);
                } catch (DeploymentException e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        }
    }


    public void validateDeployment() {
        if (!deploymentExceptions.isEmpty()) {
            RuntimeException e = JsrWebSocketMessages.MESSAGES.deploymentFailedDueToProgramaticErrors();
            for (DeploymentException ex : deploymentExceptions) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    public void deploymentComplete() {
        deploymentComplete = true;
        validateDeployment();
    }

    public List<ConfiguredServerEndpoint> getConfiguredServerEndpoints() {
        return configuredServerEndpoints;
    }

    public synchronized void close(int waitTime) {
        doClose();
        //wait for them to close
        long end = currentTimeMillis() + waitTime;
        for (ConfiguredServerEndpoint endpoint : configuredServerEndpoints) {
            endpoint.awaitClose(end - System.currentTimeMillis());
        }
    }

    @Override
    public synchronized void close() {
        close(10000);
    }

    private static class ClientNegotiation extends WebSocketClientNegotiation {

        private final ClientEndpointConfig config;

        ClientNegotiation(List<String> supportedSubProtocols, List<WebSocketExtensionData> supportedExtensions, ClientEndpointConfig config) {
            super(supportedSubProtocols, supportedExtensions);
            this.config = config;
        }

        @Override
        public void afterRequest(final HttpHeaders headers) {
            ClientEndpointConfig.Configurator configurator = config.getConfigurator();
            if (configurator != null) {
                final Map<String, List<String>> newHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Map.Entry<String, String> entry : headers) {
                    ArrayList<String> arrayList = new ArrayList<>(headers.getAll(entry.getKey()));
                    newHeaders.put(entry.getKey(), arrayList);
                }
                configurator.afterResponse(new HandshakeResponse() {
                    @Override
                    public Map<String, List<String>> getHeaders() {
                        return newHeaders;
                    }
                });
            }
            headers.remove(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            super.afterRequest(headers);
        }

        @Override
        public void beforeRequest(HttpHeaders headers) {
            ClientEndpointConfig.Configurator configurator = config.getConfigurator();
            if (configurator != null) {
                final Map<String, List<String>> newHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Map.Entry<String, String> entry : headers) {
                    ArrayList<String> arrayList = new ArrayList<>(headers.getAll(entry.getKey()));
                    newHeaders.put(entry.getKey(), arrayList);
                }
                configurator.beforeRequest(newHeaders);
                headers.clear();
                for (Map.Entry<String, List<String>> entry : newHeaders.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        headers.add(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    /**
     * Pauses the container
     *
     * @param listener
     */
    public synchronized void pause(PauseListener listener) {
        closed = true;
        if (configuredServerEndpoints.isEmpty()) {
            listener.paused();
            return;
        }
        if (listener != null) {
            pauseListeners.add(listener);
        }
        for (ConfiguredServerEndpoint endpoint : configuredServerEndpoints) {
            for (final Session session : endpoint.getOpenSessions()) {
                ((UndertowSession) session).getExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, ""));
                        } catch (Exception e) {
                            JsrWebSocketLogger.ROOT_LOGGER.couldNotCloseOnUndeploy(e);
                        }
                    }
                });
            }
        }

        Runnable done = new Runnable() {

            int count = configuredServerEndpoints.size();

            @Override
            public synchronized void run() {
                List<PauseListener> copy = null;
                synchronized (ServerWebSocketContainer.this) {
                    count--;
                    if (count == 0) {
                        copy = new ArrayList<>(pauseListeners);
                        pauseListeners.clear();
                    }
                }
                if (copy != null) {
                    for (PauseListener p : copy) {
                        p.paused();
                    }
                }
            }
        };

        for (ConfiguredServerEndpoint endpoint : configuredServerEndpoints) {
            endpoint.notifyClosed(done);
        }
    }

    private void doClose() {
        closed = true;
        for (ConfiguredServerEndpoint endpoint : configuredServerEndpoints) {
            for (Session session : endpoint.getOpenSessions()) {
                try {
                    session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, ""));
                } catch (Exception e) {
                    JsrWebSocketLogger.ROOT_LOGGER.couldNotCloseOnUndeploy(e);
                }
            }
        }
    }

    public WebSocketHandshakeHolder handshakes(ConfiguredServerEndpoint config) {
        return new WebSocketHandshakeHolder(Collections.singletonList(new Handshake(config, Collections.emptySet(), maxFrameSize)), config);
    }

    public WebSocketHandshakeHolder handshakes(ConfiguredServerEndpoint config, List<WebSocketServerExtensionHandshaker> extensions) {
        Handshake hand = new Handshake(config, Collections.emptySet(), maxFrameSize);
        for (WebSocketServerExtensionHandshaker i : extensions) {
            hand.addExtension(i);
        }
        return new WebSocketHandshakeHolder(Collections.singletonList(hand), config);
    }

    public static final class WebSocketHandshakeHolder {
        public final List<Handshake> handshakes;
        public final ConfiguredServerEndpoint endpoint;

        private WebSocketHandshakeHolder(List<Handshake> handshakes, ConfiguredServerEndpoint endpoint) {
            this.handshakes = handshakes;
            this.endpoint = endpoint;
        }
    }

    /**
     * resumes a paused container
     */
    public synchronized void resume() {
        closed = false;
        for (PauseListener p : pauseListeners) {
            p.resumed();
        }
        pauseListeners.clear();
    }

    public WebSocketReconnectHandler getWebSocketReconnectHandler() {
        return webSocketReconnectHandler;
    }

    public boolean isClosed() {
        return closed;
    }

    public interface PauseListener {
        void paused();

        void resumed();
    }

    public boolean isDispatchToWorker() {
        return dispatchToWorker;
    }


}
