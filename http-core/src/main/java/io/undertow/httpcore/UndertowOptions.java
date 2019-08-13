/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.httpcore;


import javax.security.sasl.Sasl;

/**
 * @author Stuart Douglas
 */
public class UndertowOptions {

    /**
     * The maximum size in bytes of a http request header.
     */
    public static final UndertowOption<Integer> MAX_HEADER_SIZE = UndertowOption.create("MAX_HEADER_SIZE", Integer.class);

    /**
     * The default maximum size of the HTTP entity body.
     */
    public static final UndertowOption<Long> MAX_ENTITY_SIZE = UndertowOption.create("MAX_ENTITY_SIZE", Long.class);

    /**
     * The default maximum size of the HTTP entity body when using the mutiltipart parser. Generall this will be larger than {@link #MAX_ENTITY_SIZE}.
     * <p>
     * If this is not specified it will be the same as {@link #MAX_ENTITY_SIZE}.
     */
    public static final UndertowOption<Long> MULTIPART_MAX_ENTITY_SIZE = UndertowOption.create("MULTIPART_MAX_ENTITY_SIZE", Long.class);

    /**
     * We do not have a default upload limit
     */
    public static final long DEFAULT_MAX_ENTITY_SIZE = -1;

    public static final int DEFAULT_MAX_PARAMETERS = 1000;

    /**
     * The maximum number of parameters that will be parsed. This is used to protect against hash vulnerabilities.
     * <p>
     * This applies to both query parameters, and to POST data, but is not cumulative (i.e. you can potentially have
     * max parameters * 2 total parameters).
     * <p>
     * Defaults to 1000
     */
    public static final UndertowOption<Integer> MAX_PARAMETERS = UndertowOption.create("MAX_PARAMETERS", Integer.class);

    public static final int DEFAULT_MAX_HEADERS = 200;

    /**
     * The maximum number of headers that will be parsed. This is used to protect against hash vulnerabilities.
     * <p>
     * Defaults to 200
     */
    public static final UndertowOption<Integer> MAX_HEADERS = UndertowOption.create("MAX_HEADERS", Integer.class);


    /**
     * The maximum number of cookies that will be parsed. This is used to protect against hash vulnerabilities.
     * <p>
     * Defaults to 200
     */
    public static final UndertowOption<Integer> MAX_COOKIES = UndertowOption.create("MAX_COOKIES", Integer.class);

    /**
     * If a request comes in with encoded / characters (i.e. %2F), will these be decoded.
     * <p>
     * This can cause security problems if a front end proxy does not perform the same decoding, and as a result
     * this is disabled by default.
     * <p>
     * Defaults to false
     * <p>
     * See <a href="http://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2007-0450">CVE-2007-0450</a>
     */
    public static final UndertowOption<Boolean> ALLOW_ENCODED_SLASH = UndertowOption.create("ALLOW_ENCODED_SLASH", Boolean.class);

    /**
     * If this is true then the parser will decode the URL and query parameters using the selected character encoding (UTF-8 by default). If this is false they will
     * not be decoded. This will allow a later handler to decode them into whatever charset is desired.
     * <p>
     * Defaults to true.
     */
    public static final UndertowOption<Boolean> DECODE_URL = UndertowOption.create("DECODE_URL", Boolean.class);


    /**
     * If this is true then the parser will decode the URL and query parameters using the selected character encoding (UTF-8 by default). If this is false they will
     * not be decoded. This will allow a later handler to decode them into whatever charset is desired.
     * <p>
     * Defaults to true.
     */
    public static final UndertowOption<String> URL_CHARSET = UndertowOption.create("URL_CHARSET", String.class);

    /**
     * If this is true then a Date header will be added to all responses. The HTTP spec says this header should be added to all
     * responses, unless the server does not have an accurate clock.
     * <p>
     * Defaults to true
     */
    public static final UndertowOption<Boolean> ALWAYS_SET_DATE = UndertowOption.create("ALWAYS_SET_DATE", Boolean.class);

    /**
     * Maximum size of a buffered request, in bytes
     * <p>
     * Requests are not usually buffered, the most common case is when performing SSL renegotiation for a POST request, and the post data must be fully
     * buffered in order to perform the renegotiation.
     * <p>
     * Defaults to 16384.
     */
    public static final UndertowOption<Integer> MAX_BUFFERED_REQUEST_SIZE = UndertowOption.create("MAX_BUFFERED_REQUEST_SIZE", Integer.class);

    public static final int DEFAULT_MAX_BUFFERED_REQUEST_SIZE = 16384;

    /**
     * If this is true then Undertow will record the request start time, to allow for request time to be logged
     * <p>
     * This has a small but measurable performance impact
     * <p>
     * default is false
     */
    public static final UndertowOption<Boolean> RECORD_REQUEST_START_TIME = UndertowOption.create("RECORD_REQUEST_START_TIME", Boolean.class);

    /**
     * If this is true then Undertow will allow non-escaped equals characters in unquoted cookie values.
     * <p>
     * Unquoted cookie values may not contain equals characters. If present the value ends before the equals sign. The remainder of the cookie value will be dropped.
     * <p>
     * default is false
     */
    public static final UndertowOption<Boolean> ALLOW_EQUALS_IN_COOKIE_VALUE = UndertowOption.create("ALLOW_EQUALS_IN_COOKIE_VALUE", Boolean.class);

    /**
     * If this is true then Undertow will enable RFC6265 compliant cookie validation for Set-Cookie header instead of legacy backward compatible behavior.
     * <p>
     * default is false
     */
    public static final UndertowOption<Boolean> ENABLE_RFC6265_COOKIE_VALIDATION = UndertowOption.create("ENABLE_RFC6265_COOKIE_VALIDATION", Boolean.class);

    public static final boolean DEFAULT_ENABLE_RFC6265_COOKIE_VALIDATION = false;

    /**
     * If we should attempt to use HTTP2 for HTTPS connections.
     */
    public static final UndertowOption<Boolean> ENABLE_HTTP2 = UndertowOption.create("ENABLE_HTTP2", Boolean.class);

    /**
     * The server shutdown timeout in milliseconds after which the executor will be forcefully shut down interrupting
     * tasks which are still executing.
     * <p>
     * There is no timeout by default.
     */
    public static final UndertowOption<Integer> SHUTDOWN_TIMEOUT = UndertowOption.create("SHUTDOWN_TIMEOUT", Integer.class);

    /**
     * Specify the number of accept threads a single socket server should have.  Specifying more than one can result in spurious wakeups
     * for a socket server under low connection volume, but higher throughput at high connection volume.  The minimum value
     * is 1, and the maximum value is equal to the number of available worker threads.
     */
    @Deprecated
    public static final UndertowOption<Integer> WORKER_ACCEPT_THREADS = UndertowOption.create("WORKER_ACCEPT_THREADS", Integer.class);

    private UndertowOptions() {

    }
}
