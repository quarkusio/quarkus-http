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
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.UndertowEngine;
import io.undertow.httpcore.UndertowOption;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.httpcore.UndertowOptions;
import io.undertow.server.DefaultExchangeHandler;
import io.undertow.server.HttpHandler;

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
    UndertowEngine.EngineInstance engineInstance;

    private Undertow(Builder builder) {
        this.ioThreads = builder.ioThreads;
        this.workerThreads = builder.workerThreads;
        this.listeners.addAll(builder.listeners);
        this.rootHandler = builder.handler;
        this.worker = builder.worker;
        this.bufferSize = builder.bufferSize;
        this.directBuffers = builder.directBuffers;
        this.internalWorker = builder.worker == null;
        this.serverOptions = builder.serverOptions.getMap();
    }

    /**
     * @return A builder that can be used to create an Undertow server instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public synchronized void start() {
        UndertowLogger.ROOT_LOGGER.debugf("starting undertow server %s", this);

        UndertowEngine engine = ServiceLoader.load(UndertowEngine.class).iterator().next();


        BufferAllocator allocator = new BufferAllocator() {
            @Override
            public ByteBuf allocateBuffer() {
                return PooledByteBufAllocator.DEFAULT.buffer(bufferSize);
            }

            @Override
            public ByteBuf allocateBuffer(boolean direct) {
                if (direct) {
                    return PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
                } else {
                    return PooledByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
                }
            }

            @Override
            public ByteBuf allocateBuffer(int bufferSize) {
                return PooledByteBufAllocator.DEFAULT.buffer(bufferSize);
            }

            @Override
            public ByteBuf allocateBuffer(boolean direct, int bufferSize) {
                if (direct) {
                    return PooledByteBufAllocator.DEFAULT.directBuffer(bufferSize);
                } else {
                    return PooledByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
                }
            }

            @Override
            public int getBufferSize() {
                return bufferSize;
            }
        };

        try {

            if (internalWorker) {
                worker = Executors.newFixedThreadPool(workerThreads);
            }
            engineInstance = engine.start(ioThreads, worker, allocator);

            DefaultExchangeHandler handler = new DefaultExchangeHandler(rootHandler);
            listenerInfo = new ArrayList<>();
            for (ListenerConfig listener : listeners) {
                UndertowLogger.ROOT_LOGGER.debugf("Configuring listener with getProtocol %s for interface %s and port %s", listener.type, listener.host, listener.port);
                if (listener.type == ListenerType.HTTP) {
                    engine.bindHttp(engineInstance, handler, listener.port, listener.host, listener.options, listener.blockingReadTimeout);

                } else if (listener.type == ListenerType.HTTPS) {
                    engine.bindHttps(engineInstance, handler, listener.port, listener.host, listener.keyStore, listener.keyStorePassword,
                          listener.trustStore, listener.trustStorePassword, listener.options, listener.blockingReadTimeout);
                }
            }

        } catch (Exception e) {
            if (internalWorker && worker != null) {
                worker.shutdownNow();
            }
            throw new RuntimeException(e);
        }
    }

    public synchronized void stop() {
        UndertowLogger.ROOT_LOGGER.debugf("stopping undertow server %s", this);

        if(engineInstance == null) {
            return;
        }
        engineInstance.close();
        engineInstance = null;
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
    }

    public ExecutorService getWorker() {
        return worker;
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
        final String keyStore;
        final String trustStore;
        final String keyStorePassword;
        final String trustStorePassword;

        final Object options;
        final Long blockingReadTimeout;

        private ListenerConfig(final ListenerBuilder listenerBuilder) {
            this.type = listenerBuilder.type;
            this.port = listenerBuilder.port;
            this.host = listenerBuilder.host;
            this.keyStorePassword = listenerBuilder.keyStorePassword;
            this.trustStorePassword = listenerBuilder.trustStorePassword;
            this.trustStore = listenerBuilder.trustStore;
            this.keyStore = listenerBuilder.keyStore;
            this.options = listenerBuilder.options;
            this.blockingReadTimeout = listenerBuilder.blockingReadTimeout;
        }
    }

    public static final class ListenerBuilder {

        ListenerType type;
        int port;
        String host;
        String keyStore;
        String trustStore;
        String keyStorePassword;
        String trustStorePassword;

        Object options;
        HttpHandler rootHandler;
        Long blockingReadTimeout;

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

        public ListenerBuilder setRootHandler(HttpHandler rootHandler) {
            this.rootHandler = rootHandler;
            return this;
        }

        public ListenerType getType() {
            return type;
        }

        public int getPort() {
            return port;
        }

        public String getHost() {
            return host;
        }

        public String getKeyStore() {
            return keyStore;
        }

        public ListenerBuilder setKeyStore(String keyStore) {
            this.keyStore = keyStore;
            return this;
        }

        public String getTrustStore() {
            return trustStore;
        }

        public ListenerBuilder setTrustStore(String trustStore) {
            this.trustStore = trustStore;
            return this;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public ListenerBuilder setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
            return this;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public ListenerBuilder setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
            return this;
        }

        public Object getOptions() {
            return options;
        }

        public ListenerBuilder setOptions(Object options) {
            this.options = options;
            return this;
        }

        public Long getBlockingReadTimeout() {
            return blockingReadTimeout;
        }

        public void setBlockingReadTimeout(Long blockingReadTimeout) {
            this.blockingReadTimeout = blockingReadTimeout;
        }

        public HttpHandler getRootHandler() {
            return rootHandler;
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


        public Builder addListener(ListenerBuilder listenerBuilder) {
            listeners.add(new ListenerConfig(listenerBuilder));
            return this;
        }

        public Builder addHttpListener(int port, String host) {
            listeners.add(new ListenerConfig(new ListenerBuilder().setType(ListenerType.HTTP).setHost(host).setPort(port)));
            return this;
        }

        public Builder setBufferSize(final int bufferSize) {
            this.bufferSize = bufferSize;
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

        public <T> Builder setWorker(ExecutorService worker) {
            this.worker = worker;
            return this;
        }
    }

    public static class ListenerInfo {

        private final String protcol;
        private final SocketAddress address;

        public ListenerInfo(String protcol, SocketAddress address) {
            this.protcol = protcol;
            this.address = address;
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
