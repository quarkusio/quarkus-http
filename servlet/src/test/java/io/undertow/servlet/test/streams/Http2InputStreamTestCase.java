package io.undertow.servlet.test.streams;

import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.util.DeploymentUtils;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(DefaultServer.class)
public class Http2InputStreamTestCase {

    @BeforeClass
    public static void setup() throws IOException {
        DefaultServer.startSSLServer();
        DeploymentUtils.setupServlet(
                new ServletInfo(BLOCKING_SERVLET, BlockingInputStreamServlet.class)
                        .addMapping("/" + BLOCKING_SERVLET),
                new ServletInfo(ASYNC_SERVLET, AsyncInputStreamServlet.class)
                        .addMapping("/" + ASYNC_SERVLET)
                        .setAsyncSupported(true));
    }

    @AfterClass
    public static void cleanUp() throws Exception {
        DefaultServer.stopSSLServer();
    }

    public static final String HELLO_WORLD = "Hello World";
    public static final String BLOCKING_SERVLET = "blockingInput";
    public static final String ASYNC_SERVLET = "asyncInput";

    @Test
    public void testBlockingServletInputStream() {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 1000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString();
                runTest(message, BLOCKING_SERVLET, false, false);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletInputStream() {
        //for(int h = 0; h < 20 ; ++h) {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString();
                runTest(message, ASYNC_SERVLET, false, false);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
        //}
    }

    @Test
    public void testAsyncServletInputStreamWithPreamble() {
        StringBuilder builder = new StringBuilder(2000 * HELLO_WORLD.length());
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString();
                runTest(message, ASYNC_SERVLET, true, false);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletInputStreamInParallel() throws Exception {
        StringBuilder builder = new StringBuilder(100000 * HELLO_WORLD.length());
        for (int j = 0; j < 100000; ++j) {
            builder.append(HELLO_WORLD);
        }
        String message = builder.toString();
        runTestParallel(20, message, ASYNC_SERVLET, false, false);
    }

    @Test
    public void testAsyncServletInputStreamInParallelOffIoThread() throws Exception {
        StringBuilder builder = new StringBuilder(100000 * HELLO_WORLD.length());
        for (int j = 0; j < 100000; ++j) {
            builder.append(HELLO_WORLD);
        }
        String message = builder.toString();
        runTestParallel(20, message, ASYNC_SERVLET, false, true);
    }

    @Test
    public void testAsyncServletInputStreamOffIoThread() {
        StringBuilder builder = new StringBuilder(2000 * HELLO_WORLD.length());
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString();
                runTest(message, ASYNC_SERVLET, false, true);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletInputStreamOffIoThreadWithPreamble() {
        StringBuilder builder = new StringBuilder(2000 * HELLO_WORLD.length());
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString();
                runTest(message, ASYNC_SERVLET, true, true);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletInputStreamWithEmptyRequestBody() {
        String message = "";
        try {
            runTest(message, ASYNC_SERVLET, false, false);
        } catch (Throwable e) {
            throw new RuntimeException("test failed", e);
        }
    }

    protected String getBaseUrl() {
        return DefaultServer.getDefaultServerURL();
    }

    @Test
    public void testAsyncServletInputStream3() {
        String message = "to_user_id=7999&msg_body=msg3";
        for (int i = 0; i < 200; ++i) {
            try {
                runTest(message, ASYNC_SERVLET, false, false);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    public void runTest(final String message, String url, boolean preamble, boolean offIOThread) throws Exception {
        TestHttpClient client = createClient();
        try {
            CompletableFuture<String> res = new CompletableFuture<>();
            Vertx vertx = Vertx.vertx();
            io.vertx.core.http.HttpClient httpClient = null;
            try {
                HttpClientOptions options = new HttpClientOptions().
                        setSsl(true).
                        setUseAlpn(true).
                        setProtocolVersion(HttpVersion.HTTP_2).
                        setTrustAll(true);

                httpClient = vertx.createHttpClient(options);
                HttpClientRequest request = httpClient
                        .request(HttpMethod.POST, DefaultServer.getHostSSLPort("default"),
                                DefaultServer.getHostAddress(), "/servletContext/" + url)
                        .toCompletionStage().toCompletableFuture().join();

                if (preamble && !message.isEmpty()) {
                    request.headers().add("preamble", Integer.toString(message.length() / 2));
                }
                if (offIOThread) {
                    request.headers().add("offIoThread", "true");
                }
                request.response().onSuccess(resp -> {
                    resp.body().onSuccess(b -> res.complete(b.toString(StandardCharsets.UTF_8)));
                });
                request.end(message);
                Assert.assertEquals(message, res.get(10, TimeUnit.SECONDS));
            } finally {
                try {
                    if (httpClient != null) {
                        httpClient.close().toCompletionStage().toCompletableFuture().join();
                    }
                } finally {
                    vertx.close().toCompletionStage().toCompletableFuture().join();
                }
            }
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    public void runTestParallel(int concurrency, final String message, String url, boolean preamble,
            boolean offIOThread) throws Exception {

        Vertx vertx = Vertx.vertx();
        io.vertx.core.http.HttpClient httpClient = null;
        try {
            HttpClientOptions options = new HttpClientOptions().
                    setSsl(true).
                    setUseAlpn(true).
                    setProtocolVersion(HttpVersion.HTTP_2).
                    setTrustAll(true);

            httpClient = vertx.createHttpClient(options);
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < concurrency * 5; i++) {
                HttpClientRequest request = httpClient
                        .request(HttpMethod.POST, DefaultServer.getHostSSLPort("default"),
                                DefaultServer.getHostAddress(), "/servletContext/" + url)
                        .toCompletionStage().toCompletableFuture().join();

                CompletableFuture<String> res = new CompletableFuture<>();
                if (preamble && !message.isEmpty()) {
                    request.headers().add("preamble", Integer.toString(message.length() / 2));
                }
                if (offIOThread) {
                    request.headers().add("offIoThread", "true");
                }
                request.response().onSuccess(new Handler<HttpClientResponse>() {
                    @Override
                    public void handle(HttpClientResponse resp) {
                        resp.body().onSuccess(new Handler<Buffer>() {
                            @Override
                            public void handle(Buffer b) {
                                res.complete(b.toString(StandardCharsets.UTF_8));
                            }
                        });
                    }
                });
                request.end(message);
                results.add(res);
            }
            for (Future<?> i : results) {
                i.get();
            }
        } finally {
            if (httpClient != null) {
                httpClient.close();
            }
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private static final class RateLimitedInputStream extends InputStream {
        private final InputStream in;
        private int count;

        RateLimitedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (count++ % 1000 == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
            return in.read();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    protected TestHttpClient createClient() {
        return new TestHttpClient();
    }

}
