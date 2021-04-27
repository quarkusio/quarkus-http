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
package io.undertow.websockets.jsr.test.annotated;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.undertow.Handlers;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.servlet.test.util.TestResourceLoader;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpOneOnly;
import io.undertow.testutils.HttpsIgnore;
import io.undertow.websockets.ServerWebSocketContainer;
import io.undertow.websockets.UndertowSession;
import io.undertow.websockets.WebSocketDeploymentInfo;
import io.undertow.websockets.jsr.test.FrameChecker;
import io.undertow.websockets.jsr.test.WebSocketTestClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@RunWith(DefaultServer.class)
@HttpOneOnly
public class AnnotatedEndpointTest {

    private static ServerWebSocketContainer deployment;

    @BeforeClass
    public static void setup() throws Exception {

        final ServletContainer container = ServletContainer.Factory.newInstance();

        DeploymentInfo builder = new DeploymentInfo()
                .setClassLoader(AnnotatedEndpointTest.class.getClassLoader())
                .setContextPath("/ws")
                .setResourceManager(new TestResourceLoader(AnnotatedEndpointTest.class))
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME,
                        new WebSocketDeploymentInfo()
                                .addEndpoint(MessageEndpoint.class)
                                .addEndpoint(AnnotatedClientEndpoint.class)
                                .addEndpoint(AnnotatedClientEndpointWithConfigurator.class)
                                .addEndpoint(IncrementEndpoint.class)
                                .addEndpoint(UUIDEndpoint.class)
                                .addEndpoint(EncodingEndpoint.class)
                                .addEndpoint(EncodingGenericsEndpoint.class)
                                .addEndpoint(TimeoutEndpoint.class)
                                .addEndpoint(ErrorEndpoint.class)
                                .addEndpoint(RootContextEndpoint.class)
                                .addEndpoint(ThreadSafetyEndpoint.class)
                                .addEndpoint(RequestUriEndpoint.class)
                                .addListener(new WebSocketDeploymentInfo.ContainerReadyListener() {
                                    @Override
                                    public void ready(ServerWebSocketContainer container) {
                                        deployment = container;
                                    }
                                })
                                .addEndpoint(ServerEndpointConfig.Builder.create(
                                        AnnotatedAddedProgrammaticallyEndpoint.class,
                                        AnnotatedAddedProgrammaticallyEndpoint.PATH)
                                        .build())
                )
                .addServlet(new ServletInfo("redirect", RedirectServlet.class)
                        .addMapping("/redirect"))
                .setDeploymentName("servletContext.war");


        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();


        DefaultServer.setRootHandler(Handlers.path().addPrefixPath("/ws", manager.start()));
    }

    @AfterClass
    public static void after() {
        deployment = null;
    }

    @Test
    public void testStringOnMessage() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/chat/Stuart");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    private WebSocketTestClient createTestClient(String s) throws URISyntaxException {
        return new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + s));
    }

    @Test
    public void testStringOnMessageAddedProgramatically() throws Exception {
        final byte[] payload = "foo".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/programmatic");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "oof".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    @Ignore
    public void testRedirectHandling() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/redirect"));

        Assert.assertEquals("hi Stuart (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }


    @Test
    public void testWebSocketInRootContext() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello".getBytes(), latch));
        latch.get();
        client.destroy();
    }


    @Test
    public void testAnnotatedClientEndpoint() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }

    @Test
    public void testUncleanClose() throws Exception {
        AnnotatedClientEndpoint.reset();
        MessageEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());
        UndertowSession s = (UndertowSession) session;
        s.forceClose();
        Assert.assertNotNull(MessageEndpoint.getReason());
    }


    @Test
    public void testIdleTimeout() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }


    @Test
    public void testCloseReason() throws Exception {
        AnnotatedClientEndpoint.reset();
        MessageEndpoint.reset();

        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Foo!"));
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
        CloseReason cr = MessageEndpoint.getReason();
        Assert.assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY.getCode(), cr.getCloseCode().getCode());
        Assert.assertEquals("Foo!", cr.getReasonPhrase());

    }

    @Test
    public void testAnnotatedClientEndpointWithConfigurator() throws Exception {


        Session session = deployment.connectToServer(AnnotatedClientEndpointWithConfigurator.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=configured-proto)", AnnotatedClientEndpointWithConfigurator.message());
        Assert.assertEquals("foo, bar, configured-proto", ClientConfigurator.sentSubProtocol);
        Assert.assertEquals("configured-proto", ClientConfigurator.receivedSubProtocol());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpointWithConfigurator.message());
    }

    @Test
    @Ignore("UT3 - P4")
    public void testErrorHandling() throws Exception {
        //make a sub class
        AnnotatedClientEndpoint c = new AnnotatedClientEndpoint() {

        };

        Session session = deployment.connectToServer(c, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/error"));
        Assert.assertEquals("hi", ErrorEndpoint.getMessage());
        session.getAsyncRemote().sendText("app-error");
        Assert.assertEquals("app-error", ErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.lang.RuntimeException", ErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());

        session.getBasicRemote().sendText("io-error");
        Assert.assertEquals("io-error", ErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.io.IOException", ErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());
        ((UndertowSession) session).forceClose();
        Assert.assertEquals("CLOSED", ErrorEndpoint.getMessage());

    }

    @Test
    @Ignore("UT3 - P4")
    public void testClientSideIdleTimeout() throws Exception {
        //make a sub class
        CountDownLatch latch = new CountDownLatch(1);
        CloseCountdownEndpoint c = new CloseCountdownEndpoint(latch);

        Session session = deployment.connectToServer(c, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));
        session.setMaxIdleTimeout(100);
        Assert.assertTrue(latch.await(2000, TimeUnit.MILLISECONDS));
        Assert.assertFalse(session.isOpen());

    }

    @Test
    @Ignore("UT3 - P4")
    public void testGenericMessageHandling() throws Exception {
        //make a sub class
        AnnotatedGenericClientEndpoint c = new AnnotatedGenericClientEndpoint() {

        };

        Session session = deployment.connectToServer(c, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/error"));
        Assert.assertEquals("hi", ErrorEndpoint.getMessage());
        session.getAsyncRemote().sendText("app-error");
        Assert.assertEquals("app-error", ErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.lang.RuntimeException", ErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());

        session.getBasicRemote().sendText("io-error");
        Assert.assertEquals("io-error", ErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.io.IOException", ErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());
        ((UndertowSession) session).forceClose();
        Assert.assertEquals("CLOSED", ErrorEndpoint.getMessage());

    }

    @Test
    public void testImplicitIntegerConversion() throws Exception {
        final byte[] payload = "12".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/increment/2");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "14".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    public void testEncodingAndDecodingText() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/encoding/Stuart");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    public void testPathParamDecoder() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/uuid/40164304-B94D-4332-AC31-09D7F9A8B943");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello40164304-b94d-4332-ac31-09d7f9a8b943".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    public void testEncodingAndDecodingBinary() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/encoding/Stuart");
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    public void testEncodingWithGenericSuperclass() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/encodingGenerics/Stuart");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    public void testRequestUri() throws Exception {
        final byte[] payload = "hello".getBytes();
        final CompletableFuture latch = new CompletableFuture();

        WebSocketTestClient client = createTestClient("/ws/request?a=b");
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "/ws/request?a=b".getBytes(), latch));
        latch.get();
        client.destroy();
    }

    @Test
    @Ignore("UT3 - P4")
    @HttpsIgnore("The SSL engine closes when it receives the first FIN, and as a result the web socket close frame can't be properly echoed over the proxy when the server initates the close")
    public void testTimeoutCloseReason() throws Exception {
        TimeoutEndpoint.reset();

        Session session = deployment.connectToServer(DoNothingEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/timeout"));

        Assert.assertEquals(CloseReason.CloseCodes.CLOSED_ABNORMALLY, TimeoutEndpoint.getReason().getCloseCode());
    }

    @Test
    public void testThreadSafeSend() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/threads"));
        Set<String> expected = ThreadSafetyEndpoint.expected();
        long end = System.currentTimeMillis() + 10000;
        while (!expected.isEmpty() && System.currentTimeMillis() < end) {
            expected.remove(AnnotatedClientEndpoint.message());
        }
        session.close();
        Assert.assertEquals(0, expected.size());
    }


    @Test
    public void testPingPong() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());
        session.getBasicRemote().sendPing(ByteBuffer.wrap("test ping".getBytes(StandardCharsets.UTF_8)));


        Assert.assertEquals("PONG:test ping", AnnotatedClientEndpoint.message());
        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }

    @ClientEndpoint
    public static class DoNothingEndpoint {
    }


    @ClientEndpoint
    public static class CloseCountdownEndpoint {

        private final CountDownLatch latch;

        public CloseCountdownEndpoint(CountDownLatch latch) {
            this.latch = latch;
        }

        @OnClose
        public void close() {
            latch.countDown();
        }

    }

    public static final class RedirectServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.sendRedirect("/ws/chat/Stuart");
        }
    }
}
