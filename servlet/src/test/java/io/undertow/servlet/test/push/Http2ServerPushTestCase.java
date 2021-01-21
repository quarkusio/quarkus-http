package io.undertow.servlet.test.push;

import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.SimpleServletTestCase;
import io.undertow.servlet.test.util.MessageServlet;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.testutils.DefaultServer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

@RunWith(DefaultServer.class)
public class Http2ServerPushTestCase {

    @BeforeClass
    public static void setup() throws Exception {
        DefaultServer.startSSLServer();

        final PathHandler root = new PathHandler();
        final ServletContainer container = ServletContainer.Factory.newInstance();

        ServletInfo s1 = new ServletInfo("push", ServerPushServlet.class)
                .addMapping("/push");

        ServletInfo s2 = new ServletInfo("pushed", MessageServlet.class)
                .addInitParam("message", "pushed-body")
                .addMapping("/pushed");
        ServletInfo s3 = new ServletInfo("te", TransferEncodingServlet.class)
                .addMapping("/te");
        DeploymentInfo info = new DeploymentInfo()
                .setClassLoader(SimpleServletTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setDeploymentName("servletContext.war")
                .addServlets(s1, s2, s3);


        DeploymentManager manager = container.addDeployment(info);
        manager.deploy();
        root.addPrefixPath(info.getContextPath(), manager.start());

        DefaultServer.setRootHandler(root);
    }

    @AfterClass
    public static void cleanUp() throws Exception {
        DefaultServer.stopSSLServer();
    }

    @Test
    public void testServerPush() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            HttpClientOptions options = new HttpClientOptions().
                    setSsl(true).
                    setUseAlpn(true).
                    setProtocolVersion(HttpVersion.HTTP_2).
                    setTrustAll(true);

            final CompletableFuture<String> pushedPath = new CompletableFuture<>();
            final CompletableFuture<String> pushedBody = new CompletableFuture<>();

            HttpClient client = vertx.createHttpClient(options);
            client
                    .request(HttpMethod.GET, DefaultServer.getHostSSLPort("default"), DefaultServer.getHostAddress(),
                            "/servletContext/push")
                    .toCompletionStage()
                    .thenAccept(new Consumer<HttpClientRequest>() {
                        @Override
                        public void accept(HttpClientRequest req) {
                            req.response().onComplete(new Handler<AsyncResult<HttpClientResponse>>() {
                                @Override
                                public void handle(AsyncResult<HttpClientResponse> r) {
                                    // Ignored
                                }
                            });
                            req.pushHandler(new Handler<HttpClientRequest>() {
                                @Override
                                public void handle(HttpClientRequest pushedRequest) {
                                    pushedPath.complete(pushedRequest.path());
                                    pushedRequest.response().toCompletionStage()
                                            .thenCompose(new Function<HttpClientResponse, CompletionStage<Buffer>>() {
                                                @Override
                                                public CompletionStage<Buffer> apply(
                                                        HttpClientResponse resp) {
                                                    return resp.body().toCompletionStage();
                                                }
                                            })
                                            .thenAccept(new Consumer<Buffer>() {
                                                @Override
                                                public void accept(Buffer buffer) {
                                                    pushedBody.complete(
                                                            new String(buffer.getBytes(), StandardCharsets.UTF_8));
                                                }
                                            });
                                }
                            });

                            req.end();
                        }
                    });


            Assert.assertEquals("/servletContext/pushed", pushedPath.get(10, TimeUnit.SECONDS));
            Assert.assertEquals("pushed-body", pushedBody.get(10, TimeUnit.SECONDS));

        } finally {
            vertx.close();
        }

    }

    @Test
    public void testNoTeHeader() throws Exception {

        Vertx vertx = Vertx.vertx();
        try {
            HttpClientOptions options = new HttpClientOptions().
                    setSsl(true).
                    setUseAlpn(true).
                    setProtocolVersion(HttpVersion.HTTP_2).
                    setTrustAll(true);


            final CompletableFuture<String> body = new CompletableFuture<>();
            HttpClient client = vertx.createHttpClient(options);
            client.request(HttpMethod.GET, DefaultServer.getHostSSLPort("default"), DefaultServer.getHostAddress(), "/servletContext/te")
                    .toCompletionStage()
                    .thenAccept(req -> {
                        req.response().onSuccess(resp -> {
                            if (resp.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
                                body.completeExceptionally(new RuntimeException("Transfer-Encoding header not allowed with HTTP/2"));
                            }
                            resp.body()
                                    .onSuccess(new Handler<Buffer>() {
                                        @Override
                                        public void handle(Buffer buffer) {
                                            body.complete(buffer.toString(StandardCharsets.UTF_8));
                                        }
                                    });
                        });
                        req.end();
                    });

            Assert.assertEquals("Hello world", body.get(10, TimeUnit.SECONDS));

        } finally {
            vertx.close();
        }
    }

    public static class TransferEncodingServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.getOutputStream().print("Hello ");
            resp.getOutputStream().flush();
            resp.getOutputStream().print("world");
        }
    }

}
