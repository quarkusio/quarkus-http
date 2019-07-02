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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.UndertowMessages;
import io.undertow.iocore.IoCallback;
import io.undertow.util.AbstractAttachable;
import io.undertow.util.UndertowOptionMap;
import io.vertx.core.http.ServerWebSocket;

/**
 * A server connection. This is a representation of the underlying IO layer. It is not necessarily scoped to the life of
 * the connection.
 *
 * @author Stuart Douglas
 */
public abstract class ServerConnection extends AbstractAttachable {


    protected abstract ByteBuf allocateBuffer();

    protected abstract ByteBuf allocateBuffer(boolean direct);

    protected abstract ByteBuf allocateBuffer(boolean direct, int bufferSize);

    protected abstract ByteBuf allocateBuffer(int bufferSize);

    /**
     *
     * @return The connections worker
     */
    public abstract Executor getWorker();

    /**
     *
     * @return The IO thread associated with the connection
     */
    public abstract EventExecutor getIoThread();

    /**
     * Sends a 100-continue response if it is required
     */
    public abstract void sendContinueIfRequired();


    /**
     * Write some data to the
     * @param data
     * @param last
     * @param exchange
     * @throws IOException
     */
    public abstract void writeBlocking(ByteBuf data, boolean last, HttpServerExchange exchange) throws IOException;

    public abstract <T> void writeAsync(ByteBuf data, boolean last, HttpServerExchange exchange, IoCallback<T> callback, T context);

    protected abstract boolean isIoOperationQueued();

    protected abstract <T> void scheduleIoCallback(IoCallback<T> callback, T context, HttpServerExchange exchange);

    /**
     *
     * @return <code>true</code> if this connection supports sending a 100-continue response
     */
    public abstract boolean isContinueResponseSupported();

    /**
     *
     * @return true if the connection is open
     */
    public abstract boolean isOpen();

    protected abstract void close(HttpServerExchange exchange);

    /**
     * Returns the actual address of the remote connection. This will not take things like X-Forwarded-for
     * into account.
     * @return The address of the remote peer
     */
    public abstract InetSocketAddress getPeerAddress();

    protected abstract boolean isExecutingHandlerChain();

    protected abstract void beginExecutingHandlerChain(HttpServerExchange exchange);

    protected abstract void endExecutingHandlerChain(HttpServerExchange exchange);

    public abstract InetSocketAddress getLocalAddress();

    public abstract UndertowOptionMap getUndertowOptions();

    public abstract int getBufferSize();

    /**
     * Gets SSL information about the connection
     *
     * @return SSL information about the connection
     */
    protected abstract SSLSessionInfo getSslSessionInfo();

    /**
     *
     * @return true if this connection supports HTTP upgrade
     */
    protected abstract boolean isUpgradeSupported();

    /**
     *
     * @return <code>true</code> if this connection supports the HTTP CONNECT verb
     */
    protected abstract boolean isConnectSupported();

    /**
     * Reads some data from the exchange. Can only be called if {@link #isReadDataAvailable()} returns true.
     *
     * Returns null when all data is full read
     * @return
     * @throws IOException
     */
    protected abstract void readAsync(IoCallback<ByteBuf> callback, HttpServerExchange exchange);

    protected abstract ByteBuf readBlocking(HttpServerExchange exchange) throws IOException;

    protected abstract int readBytesAvailable(HttpServerExchange exchange);

    /**
     * Callback that is invoked if the max entity size is updated.
     *
     * @param exchange The current exchange
     */
    protected abstract void maxEntitySizeUpdated(HttpServerExchange exchange);

    /**
     * Returns a string representation describing the protocol used to transmit messages
     * on this connection.
     *
     * @return the transport protocol
     */
    public abstract String getTransportProtocol();

    /**
     * Attempts to push a resource if this connection supports server push. Otherwise the request is ignored.
     *
     * Note that push is always done on a best effort basis, even if this method returns true it is possible that
     * the remote endpoint will reset the stream
     *
     *
     * @param path The path of the resource
     * @param method The request method
     * @param requestHeaders The request headers
     * @return <code>true</code> if the server attempted the push, false otherwise
     */
    public boolean pushResource(final String path, final String method, final HttpHeaders requestHeaders) {
        return false;
    }

    /**
     * Attempts to push a resource if this connection supports server push. Otherwise the request is ignored.
     *
     * Note that push is always done on a best effort basis, even if this method returns true it is possible that
     * the remote endpoint will reset the stream.
     *
     * The {@link io.undertow.server.HttpHandler} passed in will be used to generate the pushed response
     *
     *
     * @param path The path of the resource
     * @param method The request method
     * @param requestHeaders The request headers
     * @return <code>true</code> if the server attempted the push, false otherwise
     */
    public boolean pushResource(final String path, final String method, final HttpHeaders requestHeaders, HttpHandler handler) {
        return false;
    }

    public boolean isPushSupported() {
        return false;
    }

    public abstract boolean isRequestTrailerFieldsSupported();

    public abstract void runResumeReadWrite();

    public abstract <T> void writeFileAsync(RandomAccessFile file, long position, long count, HttpServerExchange exchange, IoCallback<T> context, T callback);

    public abstract void writeFileBlocking(RandomAccessFile file, long position, long count, HttpServerExchange exchange) throws IOException;


    protected  void setUpgradeListener(Consumer<ServerWebSocket> listener) {
        throw UndertowMessages.MESSAGES.upgradeNotSupported();
    }

    protected abstract void ungetRequestBytes(ByteBuf buffer, HttpServerExchange exchange);

    public abstract void discardRequest(HttpServerExchange exchange);

    public interface CloseListener {

        void closed(final ServerConnection connection);
    }
}
