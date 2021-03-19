package io.undertow.testutils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.EventExecutor;
import io.undertow.httpcore.BlockingHttpExchange;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.HttpExchangeBase;
import io.undertow.httpcore.InputChannel;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.httpcore.SSLSessionInfo;
import io.undertow.httpcore.UndertowOptionMap;

public class MockHttpExchange extends HttpExchangeBase {

    private final Map<String, String> requestHeaders = new HashMap<>();
    private final Map<String, String> responseHeaders = new HashMap<>();

    @Override
    public BufferAllocator getBufferAllocator() {
        return null;
    }

    @Override
    public HttpExchange setStatusCode(int code) {
        return null;
    }

    @Override
    public int getStatusCode() {
        return 0;
    }

    @Override
    public String getRequestHeader(String name) {
        return requestHeaders.get(name);
    }

    @Override
    public List<String> getRequestHeaders(String name) {
        return Collections.singletonList(getRequestHeader(name));
    }

    @Override
    public boolean containsRequestHeader(String name) {
        return requestHeaders.containsKey(name);
    }

    @Override
    public void removeRequestHeader(String name) {
        requestHeaders.remove(name);
    }

    @Override
    public void setRequestHeader(String name, String value) {
        requestHeaders.put(name, value);
    }

    @Override
    public Collection<String> getRequestHeaderNames() {
        return requestHeaders.keySet();
    }

    @Override
    public void addRequestHeader(String name, String value) {
        setRequestHeader(name, value);
    }

    @Override
    public void clearRequestHeaders() {
        requestHeaders.clear();
    }

    @Override
    public List<String> getResponseHeaders(String name) {
        return Collections.singletonList(responseHeaders.get(name));
    }

    @Override
    public boolean containsResponseHeader(String name) {
        return responseHeaders.containsKey(name);
    }

    @Override
    public void removeResponseHeader(String name) {
        responseHeaders.remove(name);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
    }

    @Override
    public Collection<String> getResponseHeaderNames() {
        return responseHeaders.keySet();
    }

    @Override
    public void addResponseHeader(String name, String value) {
        setRequestHeader(name, value);
    }

    @Override
    public void clearResponseHeaders() {
        responseHeaders.clear();
    }

    @Override
    public String getResponseHeader(String name) {
        return responseHeaders.get(name);
    }

    @Override
    public String getRequestMethod() {
        return null;
    }

    @Override
    public String getRequestScheme() {
        return null;
    }

    @Override
    public String getRequestURI() {
        return null;
    }

    @Override
    public String getProtocol() {
        return null;
    }

    @Override
    public boolean isInIoThread() {
        return false;
    }

    @Override
    public boolean isHttp2() {
        return false;
    }

    @Override
    public OutputChannel getOutputChannel() {
        return null;
    }

    @Override
    protected <T> void writeAsync0(ByteBuf data, boolean last, IoCallback<T> callback, T context) {

    }

    @Override
    protected void writeBlocking0(ByteBuf data, boolean last) throws IOException {

    }

    @Override
    public InputChannel getInputChannel() {
        return null;
    }

    @Override
    public InetSocketAddress getDestinationAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getSourceAddress() {
        return null;
    }

    @Override
    public void close() {

    }

    @Override
    public EventExecutor getIoThread() {
        return null;
    }

    @Override
    public void setUpgradeListener(Consumer<Object> listener) {

    }

    @Override
    public Executor getWorker() {
        return null;
    }

    @Override
    public UndertowOptionMap getUndertowOptions() {
        return null;
    }

    @Override
    public void setUndertowOptions(UndertowOptionMap options) {

    }

    @Override
    public void sendContinue() {

    }

    @Override
    public void discardRequest() {

    }

    @Override
    public boolean isUpgradeSupported() {
        return false;
    }

    @Override
    public SSLSessionInfo getSslSessionInfo() {
        return null;
    }

    @Override
    public boolean isIoOperationQueued() {
        return false;
    }

    @Override
    public void setMaxEntitySize(long maxEntitySize) {

    }

    @Override
    public long getMaxEntitySize() {
        return 0;
    }

    @Override
    public void setReadTimeout(long readTimeoutMs) {
    }

    @Override
    public long getReadTimeout() {
        return 0;
    }
}
