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
import io.vertx.core.http.HttpVersion;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

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
        DeploymentInfo info = new DeploymentInfo()
                .setClassLoader(SimpleServletTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setDeploymentName("servletContext.war")
                .addServlets(s1, s2);


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

}
