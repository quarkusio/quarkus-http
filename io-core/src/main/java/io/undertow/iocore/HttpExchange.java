package io.undertow.iocore;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface HttpExchange {

    HttpExchange endExchange();

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

    /**
     * Get the HTTP request method
     *
     * @return the HTTP request method
     */
    String getRequestMethod();

    boolean isInIoThread();

    OutputChannel getOutputChannel();

    InputChannel getInputChannel();

    InputStream getInputStream();

    OutputStream getOutputStream();
}
