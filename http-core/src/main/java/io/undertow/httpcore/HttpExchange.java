package io.undertow.httpcore;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;

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

    OutputChannel getOutputChannel();

    InputChannel getInputChannel();

    InputStream getInputStream();

    OutputStream getOutputStream();

    InetSocketAddress getDestinationAddress();

    InetSocketAddress getSourceAddress();


    /**
     * Return the host that this request was sent to, in general this will be the
     * value of the Host header, minus the port specifier.
     * <p>
     * If this resolves to an IPv6 address it will not be enclosed by square brackets.
     * Care must be taken when constructing URLs based on this method to ensure IPv6 URLs
     * are handled correctly.
     *
     * @return The host part of the destination address
     */
    default String getHostName() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host == null) {
            host = getDestinationAddress().getHostString();
        } else {
            if (host.startsWith("[")) {
                host = host.substring(1, host.indexOf(']'));
            } else if (host.indexOf(':') != -1) {
                host = host.substring(0, host.indexOf(':'));
            }
        }
        return host;
    }

    /**
     * Return the host, and also the port if this request was sent to a non-standard port. In general
     * this will just be the value of the Host header.
     * <p>
     * If this resolves to an IPv6 address it *will*  be enclosed by square brackets. The return
     * value of this method is suitable for inclusion in a URL.
     *
     * @return The host and port part of the destination address
     */
    default String getHostAndPort() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host == null) {
            InetSocketAddress address = getDestinationAddress();
            host = CoreUtils.formatPossibleIpv6Address(address.getHostString());
            int port = address.getPort();
            if (!((getRequestScheme().equals("http") && port == 80)
                    || (getRequestScheme().equals("https") && port == 443))) {
                host = host + ":" + port;
            }
        }
        return host;
    }

    /**
     * Return the port that this request was sent to. In general this will be the value of the Host
     * header, minus the host name.
     *
     * @return The port part of the destination address
     */
    default int getHostPort() {
        String host = getRequestHeader(HttpHeaderNames.HOST);
        if (host != null) {
            //for ipv6 addresses we make sure we take out the first part, which can have multiple occurrences of :
            final int colonIndex;
            if (host.startsWith("[")) {
                colonIndex = host.indexOf(':', host.indexOf(']'));
            } else {
                colonIndex = host.indexOf(':');
            }
            if (colonIndex != -1) {
                try {
                    return Integer.parseInt(host.substring(colonIndex + 1));
                } catch (NumberFormatException ignore) {
                }
            }
            if (getRequestScheme().equals("https")) {
                return 443;
            } else if (getRequestScheme().equals("http")) {
                return 80;
            }

        }
        return getDestinationAddress().getPort();
    }
}
