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
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpVersion;
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
import java.util.concurrent.TimeUnit;

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

            HttpClientRequest request = vertx.createHttpClient(options)
                    .get(DefaultServer.getHostSSLPort("default"), DefaultServer.getHostAddress(), "/servletContext/push");
            request.pushHandler(new Handler<HttpClientRequest>() {
                @Override
                public void handle(HttpClientRequest event) {
                    pushedPath.complete(event.path());
                    event.handler(new Handler<HttpClientResponse>() {
                        @Override
                        public void handle(HttpClientResponse event) {
                            event.bodyHandler(new Handler<Buffer>() {
                                @Override
                                public void handle(Buffer event) {
                                    pushedBody.complete(new String(event.getBytes(), StandardCharsets.UTF_8));
                                }
                            });
                            event.exceptionHandler(new Handler<Throwable>() {
                                @Override
                                public void handle(Throwable event) {
                                    pushedBody.completeExceptionally(event);
                                    pushedPath.completeExceptionally(event);
                                }
                            });
                        }
                    });
                }
            });
            request.handler(new Handler<HttpClientResponse>() {
                @Override
                public void handle(HttpClientResponse event) {
                    event.endHandler(new Handler<Void>() {
                        @Override
                        public void handle(Void event) {

                        }
                    });
                }
            });
            request.end();
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
            HttpClientRequest request = vertx.createHttpClient(options)
                    .get(DefaultServer.getHostSSLPort("default"), DefaultServer.getHostAddress(), "/servletContext/te");

            request.handler(new Handler<HttpClientResponse>() {
                @Override
                public void handle(HttpClientResponse event) {
                    if (event.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
                        body.completeExceptionally(new RuntimeException("Transfer-Encoding header not allowed with HTTP/2"));
                    }
                    event.bodyHandler(new Handler<Buffer>() {
                        @Override
                        public void handle(Buffer event) {
                            body.complete(event.toString(StandardCharsets.UTF_8));
                        }
                    });
                    event.exceptionHandler(new Handler<Throwable>() {
                        @Override
                        public void handle(Throwable event) {
                            body.completeExceptionally(event);
                        }
                    });
                }
            });
            request.end();
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
