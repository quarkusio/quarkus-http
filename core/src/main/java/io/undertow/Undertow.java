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

package io.undertow;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.undertow.protocol.http.VertxHttpServerInitializer;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.HttpHandler;
import io.undertow.server.OpenListener;
import io.undertow.util.UndertowOption;
import io.undertow.util.UndertowOptionMap;
import io.undertow.util.UndertowOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;

/**
 * Convenience class used to build an Undertow server.
 * <p>
 *
 * @author Stuart Douglas
 */
public final class Undertow {

    private final int ioThreads;
    private final int workerThreads;
    private final List<ListenerConfig> listeners = new ArrayList<>();
    private volatile List<ListenerInfo> listenerInfo;
    private final HttpHandler rootHandler;
    private final UndertowOptionMap workerOptions;
    private final UndertowOptionMap socketOptions;
    private final UndertowOptionMap serverOptions;
    private final int bufferSize;
    private final boolean directBuffers;

    /**
     * Will be true when a executor instance was NOT provided to the {@link Builder}.
     * When true, a new worker will be created during {@link Undertow#start()},
     * and shutdown when {@link Undertow#stop()} is called.
     * <p>
     * Will be false when a executor instance was provided to the {@link Builder}.
     * When false, the provided {@link #worker} will be used instead of creating a new one in {@link Undertow#start()}.
     * Also, when false, the {@link #worker} will NOT be shutdown when {@link Undertow#stop()} is called.
     */
    private final boolean internalWorker;

    private ExecutorService worker;
    private EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;
    List<Channel> channels;
    List<VertxHttpServerInitializer> initializers = new ArrayList<>();
    Vertx vertx;

    private Undertow(Builder builder) {
        this.ioThreads = builder.ioThreads;
        this.workerThreads = builder.workerThreads;
        this.listeners.addAll(builder.listeners);
        this.rootHandler = builder.handler;
        this.worker = builder.worker;
        this.bufferSize = builder.bufferSize;
        this.directBuffers = builder.directBuffers;
        this.internalWorker = builder.worker == null;
        this.workerOptions = builder.workerOptions.getMap();
        this.socketOptions = builder.socketOptions.getMap();
        this.serverOptions = builder.serverOptions.getMap();
    }

    /**
     * @return A builder that can be used to create an Undertow server instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public synchronized void start() {
        vertx = Vertx.vertx();
        UndertowLogger.ROOT_LOGGER.debugf("starting undertow server %s", this);
        try {

            if (internalWorker) {
                worker = Executors.newFixedThreadPool(workerThreads);
            }

            channels = new ArrayList<>();
            listenerInfo = new ArrayList<>();
            for (ListenerConfig listener : listeners) {
                UndertowLogger.ROOT_LOGGER.debugf("Configuring listener with getProtocol %s for interface %s and port %s", listener.type, listener.host, listener.port);
                final HttpHandler rootHandler = listener.rootHandler != null ? listener.rootHandler : this.rootHandler;
                if (listener.type == ListenerType.HTTP) {
                    VertxHttpServerInitializer vertxHttpServerInitializer = new VertxHttpServerInitializer(worker, rootHandler, bufferSize, vertx, directBuffers, ioThreads);
                    vertxHttpServerInitializer.runServer(listener.host, listener.port, new HttpServerOptions());
                    initializers.add(vertxHttpServerInitializer);

                } else if (listener.type == ListenerType.HTTPS) {
                    VertxHttpServerInitializer vertxHttpServerInitializer = new VertxHttpServerInitializer(worker, rootHandler, bufferSize, vertx, directBuffers, ioThreads);
                    vertxHttpServerInitializer.runServer(listener.host, listener.port, new HttpServerOptions().setSsl(true)
                            .setKeyStoreOptions(listener.keyStoreOptions)
                            .setTrustStoreOptions(listener.trustStoreOptions));
                    initializers.add(vertxHttpServerInitializer);
                }
            }

        } catch (Exception e) {
            if (internalWorker && worker != null) {
                worker.shutdownNow();
            }
            throw new RuntimeException(e);
        }
    }

    private ServerBootstrap bootstrap() {
        ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        return new ServerBootstrap()
                .option(ChannelOption.ALLOCATOR, allocator)
                .option(ChannelOption.SO_BACKLOG, socketOptions.get(UndertowOptions.BACKLOG, 1024))
                .option(ChannelOption.SO_REUSEADDR, socketOptions.get(UndertowOptions.REUSE_ADDRESSES, true))
                .childOption(ChannelOption.ALLOCATOR, allocator)
                .childOption(ChannelOption.SO_KEEPALIVE, socketOptions.get(UndertowOptions.KEEP_ALIVE, false))
                .childOption(ChannelOption.TCP_NODELAY, socketOptions.get(UndertowOptions.TCP_NODELAY, true))
                // Requires EpollServerSocketChannel
//                .childOption(EpollChannelOption.TCP_CORK, socketOptions.get(UndertowOptions.CORK, true))
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class);
    }


    public synchronized void stop() {
        if(bossGroup == null) {
            return;
        }
        UndertowLogger.ROOT_LOGGER.debugf("stopping undertow server %s", this);
        if (channels != null) {
            for (VertxHttpServerInitializer channel : initializers) {
                channel.close();
            }
            channels = null;
        }

        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();

        /*
         * Only shutdown the worker if it was created during start()
         */
        if (internalWorker && worker != null) {
            Integer shutdownTimeoutMillis = serverOptions.get(UndertowOptions.SHUTDOWN_TIMEOUT);
            worker.shutdown();
            try {
                if (shutdownTimeoutMillis == null) {
                    //worker.awaitTermination();
                } else {
                    if (!worker.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                        worker.shutdownNow();
                    }
                }
            } catch (InterruptedException e) {
                worker.shutdownNow();
                throw new RuntimeException(e);
            }
            worker = null;
        }
        listenerInfo = null;
        vertx.close();
    }

    public ExecutorService getWorker() {
        return worker;
    }

    public EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    public List<ListenerInfo> getListenerInfo() {
        if (listenerInfo == null) {
            throw UndertowMessages.MESSAGES.serverNotStarted();
        }
        return Collections.unmodifiableList(listenerInfo);
    }


    public enum ListenerType {
        HTTP,
        HTTPS
    }

    private static class ListenerConfig {

        final ListenerType type;
        final int port;
        final String host;
        final JksOptions keyStoreOptions;
        final JksOptions trustStoreOptions;
        final HttpHandler rootHandler;
        final UndertowOptionMap overrideSocketOptions;
        final boolean useProxyProtocol;

        private ListenerConfig(final ListenerType type, final int port, final String host, JksOptions keyStoreOptions, JksOptions trustStoreOptions, HttpHandler rootHandler) {
            this.type = type;
            this.port = port;
            this.host = host;
            this.keyStoreOptions = keyStoreOptions;
            this.trustStoreOptions = trustStoreOptions;
            this.rootHandler = rootHandler;
            this.overrideSocketOptions = UndertowOptionMap.EMPTY;
            this.useProxyProtocol = false;
        }

        private ListenerConfig(final ListenerBuilder listenerBuilder) {
            this.type = listenerBuilder.type;
            this.port = listenerBuilder.port;
            this.host = listenerBuilder.host;
            this.rootHandler = listenerBuilder.rootHandler;
            this.keyStoreOptions = listenerBuilder.keyStoreOptions;
            this.trustStoreOptions = listenerBuilder.trustStoreOptions;
            this.overrideSocketOptions = listenerBuilder.overrideSocketOptions;
            this.useProxyProtocol = listenerBuilder.useProxyProtocol;
        }
    }

    public static final class ListenerBuilder {

        ListenerType type;
        int port;
        String host;
        JksOptions keyStoreOptions;
        JksOptions trustStoreOptions;
        SSLContext sslContext;
        HttpHandler rootHandler;
        UndertowOptionMap overrideSocketOptions = UndertowOptionMap.EMPTY;
        boolean useProxyProtocol;

        public ListenerBuilder setType(ListenerType type) {
            this.type = type;
            return this;
        }

        public ListenerBuilder setPort(int port) {
            this.port = port;
            return this;
        }

        public ListenerBuilder setHost(String host) {
            this.host = host;
            return this;
        }

        public ListenerBuilder setKeyStoreOptions(JksOptions keyStoreOptions) {
            this.keyStoreOptions = keyStoreOptions;
            return this;
        }

        public ListenerBuilder setTrustStoreOptions(JksOptions trustStoreOptions) {
            this.trustStoreOptions = trustStoreOptions;
            return this;
        }

        public ListenerBuilder setSslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public ListenerBuilder setRootHandler(HttpHandler rootHandler) {
            this.rootHandler = rootHandler;
            return this;
        }

        public ListenerBuilder setOverrideSocketOptions(UndertowOptionMap overrideSocketOptions) {
            this.overrideSocketOptions = overrideSocketOptions;
            return this;
        }

        public ListenerBuilder setUseProxyProtocol(boolean useProxyProtocol) {
            this.useProxyProtocol = useProxyProtocol;
            return this;
        }
    }

    public static final class Builder {

        int bufferSize;
        int ioThreads;
        int workerThreads;
        boolean directBuffers;
        final List<ListenerConfig> listeners = new ArrayList<>();
        HttpHandler handler;
        ExecutorService worker;

        final UndertowOptionMap.Builder workerOptions = UndertowOptionMap.builder();
        final UndertowOptionMap.Builder socketOptions = UndertowOptionMap.builder();
        final UndertowOptionMap.Builder serverOptions = UndertowOptionMap.builder();

        private Builder() {
            ioThreads = Math.max(Runtime.getRuntime().availableProcessors(), 2);
            workerThreads = ioThreads * 8;
            long maxMemory = Runtime.getRuntime().maxMemory();
            //smaller than 64mb of ram we use 512b buffers
            if (maxMemory < 64 * 1024 * 1024) {
                //use 512b buffers
                directBuffers = false;
                bufferSize = 512;
            } else if (maxMemory < 128 * 1024 * 1024) {
                //use 1k buffers
                directBuffers = true;
                bufferSize = 1024;
            } else {
                //use 16k buffers for best performance
                //as 16k is generally the max amount of data that can be sent in a single write() call
                directBuffers = true;
                bufferSize = 1024 * 16 - 20; //the 20 is to allow some space for getProtocol headers, see UNDERTOW-1209
            }

        }

        public Undertow build() {
            return new Undertow(this);
        }

        @Deprecated
        public Builder addListener(int port, String host) {
            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host, null, null, null));
            return this;
        }

        @Deprecated
        public Builder addListener(int port, String host, ListenerType listenerType) {
            listeners.add(new ListenerConfig(listenerType, port, host, null, null, null));
            return this;
        }

        public Builder addListener(ListenerBuilder listenerBuilder) {
            listeners.add(new ListenerConfig(listenerBuilder));
            return this;
        }

        public Builder addHttpListener(int port, String host) {
            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host, null, null, null));
            return this;
        }

        public Builder addHttpsListener(int port, String host, JksOptions keyManagers, JksOptions trustManagers) {
            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, keyManagers, trustManagers, null));
            return this;
        }

        public Builder addHttpListener(int port, String host, HttpHandler rootHandler) {
            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host, null, null, rootHandler));
            return this;
        }

        public Builder addHttpsListener(int port, String host, JksOptions keystoreOptions, JksOptions trustStoreOptions, HttpHandler rootHandler) {
            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, keystoreOptions, trustStoreOptions, rootHandler));
            return this;
        }


        public Builder setBufferSize(final int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        @Deprecated
        public Builder setBuffersPerRegion(final int buffersPerRegion) {
            return this;
        }

        public Builder setIoThreads(final int ioThreads) {
            this.ioThreads = ioThreads;
            return this;
        }

        public Builder setWorkerThreads(final int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder setDirectBuffers(final boolean directBuffers) {
            this.directBuffers = directBuffers;
            return this;
        }

        public Builder setHandler(final HttpHandler handler) {
            this.handler = handler;
            return this;
        }

        public <T> Builder setServerOption(final UndertowOption<T> option, final T value) {
            serverOptions.set(option, value);
            return this;
        }

        public <T> Builder setSocketOption(final UndertowOption<T> option, final T value) {
            socketOptions.set(option, value);
            return this;
        }

        public <T> Builder setWorkerOption(final UndertowOption<T> option, final T value) {
            workerOptions.set(option, value);
            return this;
        }

        public <T> Builder setWorker(ExecutorService worker) {
            this.worker = worker;
            return this;
        }
    }

    public static class ListenerInfo {

        private final String protcol;
        private final SocketAddress address;
        private final OpenListener openListener;
        private volatile boolean suspended = false;

        public ListenerInfo(String protcol, SocketAddress address, OpenListener openListener) {
            this.protcol = protcol;
            this.address = address;
            this.openListener = openListener;
        }

        public String getProtcol() {
            return protcol;
        }

        public SocketAddress getAddress() {
            return address;
        }

        public SSLContext getSslContext() {
            return null;
        }

        public void setSslContext(SSLContext sslContext) {
//sslContext            if (ssl != null) {
//                //just ignore it if this is not a SSL listener
//                ssl.updateSSLContext(sslContext);
//            }
        }

        public synchronized void suspend() {
//            suspended = true;
//            channel.suspendAccepts();
//            CountDownLatch latch = new CountDownLatch(1);
//            //the channel may be in the middle of an accept, we need to close from the IO thread
//            channel.getIoThread().execute(new Runnable() {
//                @Override
//                public void run() {
//                    try {
//                        openListener.closeConnections();
//                    } finally {
//                        latch.countDown();
//                    }
//                }
//            });
//            try {
//                latch.await();
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
        }

        public synchronized void resume() {
//            suspended = false;
//            channel.resumeAccepts();
        }

        public boolean isSuspended() {
            return suspended;
        }

        public ConnectorStatistics getConnectorStatistics() {
            return openListener.getConnectorStatistics();
        }
//
//        public <T> void setSocketOption(Option<T> option, T value) throws IOException {
//            channel.setOption(option, value);
//        }

        public void setServerOptions(UndertowOptionMap options) {
            openListener.setUndertowOptions(options);
        }

        @Override
        public String toString() {
            return "ListenerInfo{" +
                    "protcol='" + protcol + '\'' +
                    ", address=" + address +
                    ", sslContext=" + getSslContext() +
                    '}';
        }
    }

}
