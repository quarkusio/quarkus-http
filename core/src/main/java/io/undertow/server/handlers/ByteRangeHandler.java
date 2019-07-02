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

package io.undertow.server.handlers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ResponseCommitListener;
import io.undertow.server.WriteFunction;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.util.ByteRange;
import io.undertow.util.DateUtils;
import io.undertow.util.HttpHeaderNames;
import io.undertow.util.HttpMethodNames;
import io.undertow.util.StatusCodes;

/**
 * Handler for Range requests. This is a generic handler that can handle range requests to any resource
 * of a fixed content length i.e. any resource where the content-length header has been set.
 * <p>
 * Note that this is not necessarily the most efficient way to handle range requests, as the full content
 * will be generated and then discarded.
 * <p>
 * At present this handler can only handle simple (i.e. single range) requests. If multiple ranges are requested the
 * Range header will be ignored.
 *
 * @author Stuart Douglas
 */
public class ByteRangeHandler implements HttpHandler {

    private final HttpHandler next;
    private final boolean sendAcceptRanges;

    private static final ResponseCommitListener ACCEPT_RANGE_LISTENER = new ResponseCommitListener() {
        @Override
        public void beforeCommit(HttpServerExchange exchange) {
            if (!exchange.containsResponseHeader(HttpHeaderNames.ACCEPT_RANGES)) {
                if (exchange.containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                    exchange.setResponseHeader(HttpHeaderNames.ACCEPT_RANGES, "bytes");
                } else {
                    exchange.setResponseHeader(HttpHeaderNames.ACCEPT_RANGES, "none");
                }
            }
        }

    };

    public ByteRangeHandler(HttpHandler next, boolean sendAcceptRanges) {
        this.next = next;
        this.sendAcceptRanges = sendAcceptRanges;
    }


    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        //range requests are only support for GET requests as per the RFC
        if (!HttpMethodNames.GET.equals(exchange.getRequestMethod()) && !HttpMethodNames.HEAD.equals(exchange.getRequestMethod())) {
            next.handleRequest(exchange);
            return;
        }
        if (sendAcceptRanges) {
            exchange.addResponseCommitListener(ACCEPT_RANGE_LISTENER);
        }
        final ByteRange range = ByteRange.parse(exchange.getRequestHeader(HttpHeaderNames.RANGE));
        if (range != null && range.getRanges() == 1) {
            exchange.addResponseCommitListener(new ResponseCommitListener() {
                @Override
                public void beforeCommit(HttpServerExchange exchange) {
                    if (exchange.getStatusCode() != StatusCodes.OK) {
                        return;
                    }
                    String length = exchange.getResponseHeader(HttpHeaderNames.CONTENT_LENGTH);
                    if (length == null) {
                        return;
                    }
                    long responseLength = Long.parseLong(length);
                    String lastModified = exchange.getResponseHeader(HttpHeaderNames.LAST_MODIFIED);
                    ByteRange.RangeResponseResult rangeResponse = range.getResponseResult(responseLength, exchange.getRequestHeader(HttpHeaderNames.IF_RANGE),
                            lastModified == null ? null : DateUtils.parseDate(lastModified), exchange.getResponseHeader(HttpHeaderNames.ETAG));
                    if (rangeResponse != null) {
                        long start = rangeResponse.getStart();
                        long end = rangeResponse.getEnd();
                        exchange.setStatusCode(rangeResponse.getStatusCode());
                        exchange.setResponseHeader(HttpHeaderNames.CONTENT_RANGE, rangeResponse.getContentRange());
                        exchange.setResponseContentLength(rangeResponse.getContentLength());
                        if (rangeResponse.getStatusCode() == StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE) {
                            exchange.addWriteFunction(new WriteFunction() {
                                @Override
                                public ByteBuf preWrite(ByteBuf data, boolean last) {
                                    data.release();
                                    return Unpooled.EMPTY_BUFFER;
                                }
                            });
                        }
                        exchange.addWriteFunction(new RangeWriteFunction(start, end, responseLength));
                    }
                }

            });
        }
        next.handleRequest(exchange);

    }

    private class RangeWriteFunction implements WriteFunction {

        private final long start, end;
        private final long originalResponseLength;

        private long written;

        public RangeWriteFunction(long start, long end, long originalResponseLength) {
            this.start = start;
            this.end = end;
            this.originalResponseLength = originalResponseLength;
        }

        @Override
        public ByteBuf preWrite(ByteBuf src, boolean last) {
            if(src == null) {
                return null;
            }
            if (written > end) {
                src.release();
                return Unpooled.EMPTY_BUFFER;
            }
            if (written < start) {
                long toEat = start - written;
                if (src.readableBytes() < toEat) {
                    written += src.readableBytes();
                    src.release();
                    return Unpooled.EMPTY_BUFFER;
                } else {
                    src.readerIndex((int) (src.readerIndex() + toEat));
                    written += toEat;
                }
            }
            long remaining = end - written + 1;
            if (src.readableBytes() > remaining) {
                src.writerIndex((int) (src.readerIndex() + remaining));
                written += remaining;
            } else {
                written += src.readableBytes();
            }
            return src;
        }


    }


    public static class Wrapper implements HandlerWrapper {

        private final boolean sendAcceptRanges;

        public Wrapper(boolean sendAcceptRanges) {
            this.sendAcceptRanges = sendAcceptRanges;
        }

        @Override
        public HttpHandler wrap(HttpHandler handler) {
            return new ByteRangeHandler(handler, sendAcceptRanges);
        }
    }

    public static class Builder implements HandlerBuilder {

        @Override
        public String name() {
            return "byte-range";
        }

        @Override
        public Map<String, Class<?>> parameters() {
            Map<String, Class<?>> params = new HashMap<>();
            params.put("send-accept-ranges", boolean.class);
            return params;
        }

        @Override
        public Set<String> requiredParameters() {
            return Collections.emptySet();
        }

        @Override
        public String defaultParameter() {
            return "send-accept-ranges";
        }

        @Override
        public HandlerWrapper build(Map<String, Object> config) {
            Boolean send = (Boolean) config.get("send-accept-ranges");
            return new Wrapper(send != null && send);
        }
    }


}
