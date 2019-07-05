package io.undertow.httpcore;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.netty.util.concurrent.EventExecutor;

public interface HttpExchange extends Closeable {

    BufferAllocator getBufferAllocator();

    HttpExchange setStatusCode(int code);

    int getStatusCode();

    /**
     * Gets the first request header with the given name
     * @param name The header name
     * @return The header value, or null if it is not present
     */
    String getRequestHeader(String name);

    /**
     * Gets request headers with the given name
     * @param name The header name
     * @return The header value, or an empty list if none are present
     */
    List<String> getRequestHeaders(String name);
    boolean containsRequestHeader(String name);
    void removeRequestHeader(String name);
    void setRequestHeader(String name, String value);
    Collection<String> getRequestHeaderNames();
    void addRequestHeader(String name, String value);
    void clearRequestHeaders();

    /**
     * Gets response headers with the given name
     * @param name The header name
     * @return The header value, or an empty list if none are present
     */
    List<String> getResponseHeaders(String name);
    boolean containsResponseHeader(String name);
    void removeResponseHeader(String name);
    void setResponseHeader(String name, String value);
    Collection<String> getResponseHeaderNames();
    void addResponseHeader(String name, String value);
    void clearResponseHeaders();

    /**
     * Gets the first response header with the given name
     * @param name The header name
     * @return The header value, or null if it is not present
     */
    String getResponseHeader(String name);

    void setCompletedListener(CompletedListener listener);
    void setPreCommitListener(PreCommitListener listener);

    /**
     * @return The content length of the request, or <code>-1</code> if it has not been set
     */
    default long getRequestContentLength() {
        String contentLengthString = getRequestHeader(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLengthString == null) {
            return -1;
        }
        return Long.parseLong(contentLengthString);
    }

    /**
     * @return The content length of the response, or <code>-1</code> if it has not been set
     */
    default long getResponseContentLength() {
        String contentLengthString = getResponseHeader(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLengthString == null) {
            return -1;
        }
        return Long.parseLong(contentLengthString);
    }

    /**
     * @return True if this exchange represents an upgrade response
     */
    default boolean isUpgrade() {
        return getStatusCode() == StatusCodes.SWITCHING_PROTOCOLS;
    }

    /**
     * Get the HTTP request method
     *
     * @return the HTTP request method
     */
    String getRequestMethod();

    /**
     * Get the request URI scheme.  Normally this is one of {@code http} or {@code https}.
     *
     * @return the request URI scheme
     */
    String getRequestScheme();

    /**
     * The original request URI. This will include the host name, getProtocol etc
     * if it was specified by the client.
     * <p>
     * This is not decoded in any way, and does not include the query string.
     * <p>
     * Examples:
     * GET http://localhost:8080/myFile.jsf?foo=bar HTTP/1.1 -&gt; 'http://localhost:8080/myFile.jsf'
     * POST /my+File.jsf?foo=bar HTTP/1.1 -&gt; '/my+File.jsf'
     */
    String getRequestURI();

    String getProtocol();

    boolean isInIoThread();

    void addWriteFunction(WriteFunction listener);

    OutputChannel getOutputChannel();

    InputChannel getInputChannel();

    InputStream getInputStream();

    OutputStream getOutputStream();

    void setBlockingHttpExchange(BlockingHttpExchange exchange);


    InetSocketAddress getDestinationAddress();

    InetSocketAddress getSourceAddress();


    boolean isComplete();

    /**
     * Returns true if all data has been read from the request, or if there
     * was not data.
     *
     * @return true if the request is complete
     */
    boolean isRequestComplete();

    /**
     * @return true if the responses is complete
     */
    boolean isResponseComplete();

    void close();

    EventExecutor getIoThread();

    void setUpgradeListener(Consumer<Object> listener);

    Executor getWorker();

    UndertowOptionMap getUndertowOptions();

    void sendContinue();

    void discardRequest();

    boolean isUpgradeSupported();

    SSLSessionInfo getSslSessionInfo();

    default boolean isPushSupported() {
        return false;
    }

    default boolean isRequestTrailerFieldsSupported() {
        return false;
    }

    boolean isIoOperationQueued();

    void setMaxEntitySize(long maxEntitySize);

    long getMaxEntitySize();

    void endExchange();

}
