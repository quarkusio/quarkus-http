package io.undertow.server.handlers.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.undertow.UndertowLogger;
import io.undertow.iocore.OutputChannel;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.DateUtils;
import io.undertow.util.ETag;
import io.undertow.util.MimeMappings;

/**
 * A path resource
 *
 * @author Stuart Douglas
 */
public class PathResource implements RangeAwareResource {

    private final Path file;
    private final String path;
    private final ETag eTag;
    private final PathResourceManager manager;

    public PathResource(final Path file, final PathResourceManager manager, String path, ETag eTag) {
        this.file = file;
        this.path = path;
        this.manager = manager;
        this.eTag = eTag;
    }

    public PathResource(final Path file, final PathResourceManager manager, String path) {
        this(file, manager, path, null);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Date getLastModified() {
        try {
            return new Date(Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getLastModifiedString() {
        return DateUtils.toDateString(getLastModified());
    }

    @Override
    public ETag getETag() {
        return eTag;
    }

    @Override
    public String getName() {
        return file.getFileName().toString();
    }

    @Override
    public boolean isDirectory() {
        return Files.isDirectory(file);
    }

    @Override
    public List<Resource> list() {
        final List<Resource> resources = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file)) {
            for (Path child : stream) {
                resources.add(new PathResource(child, manager, path + File.separator + child.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return resources;
    }

    @Override
    public String getContentType(final MimeMappings mimeMappings) {
        final String fileName = file.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        if (index != -1 && index != fileName.length() - 1) {
            return mimeMappings.getMimeType(fileName.substring(index + 1));
        }
        return null;
    }

    @Override
    public void serveBlocking(final OutputStream sender, final HttpServerExchange exchange) throws IOException {
        ByteBuf buffer = exchange.allocateBuffer(false);
        try (InputStream in = Files.newInputStream(file)) {
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
        //TODO: do this properly
        //todo implement non blocking IO
        exchange.dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    exchange.startBlocking();
                    serveBlocking(exchange.getOutputStream(), exchange);
                } catch (IOException e) {
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                    exchange.endExchange();
                }
            }
        });
    }

    @Override
    public Long getContentLength() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getCacheKey() {
        return file.toString();
    }

    @Override
    public File getFile() {
        return file.toFile();
    }

    @Override
    public Path getFilePath() {
        return file;
    }

    @Override
    public File getResourceManagerRoot() {
        return manager.getBasePath().toFile();
    }

    @Override
    public Path getResourceManagerRootPath() {
        return manager.getBasePath();
    }

    @Override
    public URL getUrl() {
        try {
            return file.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void serveRangeBlocking(OutputStream sender, HttpServerExchange exchange, long start, long end) throws IOException {
        ByteBuf buffer = exchange.allocateBuffer(false);
        int pos = 0;
        try (InputStream in = Files.newInputStream(file)) {
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
        } finally {
            buffer.release();
        }
    }

    @Override
    public void serveRangeAsync(OutputChannel outputStream, HttpServerExchange exchange, long start, long end) {
        //todo implement non blocking IO
        exchange.dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    exchange.startBlocking();
                    serveRangeBlocking(exchange.getOutputStream(), exchange, start, end);
                } catch (IOException e) {
                    UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                    exchange.endExchange();
                }
            }
        });
    }

    @Override
    public boolean isRangeSupported() {
        return true;
    }
}
