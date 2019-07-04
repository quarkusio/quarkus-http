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

package io.undertow.server.handlers.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.undertow.UndertowLogger;
import io.undertow.httpcore.HttpExchange;
import io.undertow.httpcore.IoCallback;
import io.undertow.httpcore.OutputChannel;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.DateUtils;
import io.undertow.util.ETag;
import io.undertow.util.IoUtils;
import io.undertow.util.MimeMappings;
import io.undertow.httpcore.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class URLResource implements Resource, RangeAwareResource {

    private final URL url;
    private final String path;

    private boolean connectionOpened = false;
    private Date lastModified;
    private Long contentLength;

    @Deprecated
    public URLResource(final URL url, URLConnection connection, String path) {
        this(url, path);
    }

    public URLResource(final URL url, String path) {
        this.url = url;
        this.path = path;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Date getLastModified() {
        openConnection();
        return lastModified;
    }

    private void openConnection() {
        if (!connectionOpened) {
            connectionOpened = true;
            URLConnection connection = null;
            try {
                try {
                    connection = url.openConnection();
                } catch (IOException e) {
                    lastModified = null;
                    contentLength = null;
                    return;
                }
                if (url.getProtocol().equals("jar")) {
                    connection.setUseCaches(false);
                    URL jar = ((JarURLConnection) connection).getJarFileURL();
                    lastModified = new Date(new File(jar.getFile()).lastModified());
                } else {
                    lastModified = new Date(connection.getLastModified());
                }
                contentLength = connection.getContentLengthLong();
            } finally {
                if (connection != null) {
                    try {
                        IoUtils.safeClose(connection.getInputStream());
                    } catch (IOException e) {
                        //ignore
                    }
                }
            }
        }
    }

    @Override
    public String getLastModifiedString() {
        return DateUtils.toDateString(getLastModified());
    }

    @Override
    public ETag getETag() {
        return null;
    }

    @Override
    public String getName() {
        String path = url.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int sepIndex = path.lastIndexOf("/");
        if (sepIndex != -1) {
            path = path.substring(sepIndex + 1);
        }
        return path;
    }

    @Override
    public boolean isDirectory() {
        Path file = getFilePath();
        if (file != null) {
            return Files.isDirectory(file);
        } else if (url.getPath().endsWith("/")) {
            return true;
        }
        return false;
    }

    @Override
    public List<Resource> list() {
        List<Resource> result = new LinkedList<>();
        Path file = getFilePath();
        try {
            if (file != null) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(file)) {
                    for (Path child : stream) {
                        result.add(new URLResource(child.toUri().toURL(), child.toString()));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public String getContentType(final MimeMappings mimeMappings) {
        final String fileName = getName();
        int index = fileName.lastIndexOf('.');
        if (index != -1 && index != fileName.length() - 1) {
            return mimeMappings.getMimeType(fileName.substring(index + 1));
        }
        return null;
    }

    @Override
    public void serveBlocking(final OutputStream sender, final HttpServerExchange exchange) throws IOException {
        ByteBuf buffer = exchange.allocateBuffer(false);
        try (InputStream in = url.openStream()) {
            int r;
            while ((r = in.read(buffer.array(), buffer.arrayOffset(), buffer.writableBytes())) > 0) {
                sender.write(buffer.array(), buffer.arrayOffset(), r);
            }
        } finally {
            buffer.release();
        }
    }

    @Override
    public void serveAsync(OutputChannel stream, HttpServerExchange exchange) {
        serveImpl(stream, exchange, -1, -1, false);
    }

    public void serveImpl(final OutputChannel stream, final HttpServerExchange exchange, final long start, final long end, final boolean range) {

        class ServerTask implements Runnable, IoCallback<Void> {

            private InputStream inputStream;
            private byte[] buffer;


            long toSkip = start;
            long remaining = end - start + 1;

            @Override
            public void run() {
                if (range && remaining == 0) {
                    //we are done, just return
                    IoUtils.safeClose(inputStream);
                    exchange.endExchange();
                    return;
                }
                if (inputStream == null) {
                    try {
                        inputStream = url.openStream();
                    } catch (IOException e) {
                        exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                        return;
                    }
                    buffer = new byte[1024];//TODO: we should be pooling these
                    exchange.addExchangeCompleteListener(new ExchangeCompletionListener() {
                        @Override
                        public void exchangeEvent(HttpServerExchange exchange) {
                            IoUtils.safeClose(inputStream);
                        }
                    });
                }
                try {
                    int res = inputStream.read(buffer);
                    if (res == -1) {
                        //we are done, just return
                        IoUtils.safeClose(inputStream);
                        exchange.endExchange();
                        return;
                    }
                    int bufferStart = 0;
                    int length = res;
                    if (range && toSkip > 0) {
                        //skip to the start of the requested range
                        //not super efficient, but what can you do
                        while (toSkip > res) {
                            toSkip -= res;
                            res = inputStream.read(buffer);
                            if (res == -1) {
                                //we are done, just return
                                IoUtils.safeClose(inputStream);
                                exchange.endExchange();
                                return;
                            }
                        }
                        bufferStart = (int) toSkip;
                        length -= toSkip;
                        toSkip = 0;
                    }
                    if (range && length > remaining) {
                        length = (int) remaining;
                    }
                    remaining -= length;
                    stream.writeAsync(Unpooled.wrappedBuffer(buffer, bufferStart, length), remaining == 0, this, null);
                } catch (IOException e) {
                    exchange.endExchange();
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                }

            }

            @Override
            public void onComplete(HttpExchange ex, Void context) {

                if (exchange.isInIoThread()) {
                    exchange.dispatch(this);
                } else {
                    run();
                }
            }

            @Override
            public void onException(HttpExchange exchange, Void context, IOException exception) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                exchange.close();
            }
        }

        ServerTask serveTask = new ServerTask();
        if (exchange.isInIoThread()) {
            exchange.dispatch(serveTask);
        } else {
            serveTask.run();
        }
    }

    @Override
    public Long getContentLength() {
        openConnection();
        return contentLength;
    }

    @Override
    public String getCacheKey() {
        return url.toString();
    }

    @Override
    public File getFile() {
        Path path = getFilePath();
        return path != null ? path.toFile() : null;
    }

    @Override
    public Path getFilePath() {
        if (url.getProtocol().equals("file")) {
            try {
                return Paths.get(url.toURI());
            } catch (URISyntaxException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public File getResourceManagerRoot() {
        return null;
    }

    @Override
    public Path getResourceManagerRootPath() {
        return null;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void serveRangeBlocking(OutputStream sender, HttpServerExchange exchange, long start, long end) throws IOException {
        ByteBuf buffer = exchange.allocateBuffer(false);
        int pos = 0;
        try (InputStream in = url.openStream()) {
            int r;
            while ((r = in.read(buffer.array(), buffer.arrayOffset(), buffer.writableBytes())) > 0) {
                int sw, ew;
                if (pos < start) {
                    int toEat = (int) (start - pos);
                    if (toEat > r) {
                        sw = -1;
                    } else {
                        sw = toEat;
                    }
                } else {
                    sw = 0;
                }
                int rem = (int) (end - Math.max(pos, start)) + 1;
                if (rem <= 0) {
                    sender.close();
                    return;
                } else if (r > rem) {
                    ew = rem;
                } else {
                    ew = r;
                }
                if (sw != -1) {
                    sender.write(buffer.array(), buffer.arrayOffset() + sw, ew);
                }
                pos += r;
            }
            sender.close();
        } finally {
            buffer.release();
        }
    }

    @Override
    public void serveRangeAsync(OutputChannel outputStream, HttpServerExchange exchange, long start, long end) {
        serveImpl(outputStream, exchange, start, end, true);
    }

    @Override
    public boolean isRangeSupported() {
        return true;
    }
}
