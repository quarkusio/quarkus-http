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

package io.undertow.servlet.test.streams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.util.DeploymentUtils;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.httpcore.StatusCodes;

/**
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.class)
public class ServletOutputStreamTestCase {

    public static String message;

    public static final String HELLO_WORLD = "Hello World";
    public static final String BLOCKING_SERVLET = "blockingOutput";
    public static final String ASYNC_SERVLET = "asyncOutput";
    public static final String CONTENT_LENGTH_SERVLET = "contentLength";
    public static final String FLUSH_BUFFER_SERVLET = "flushBuffer";
    public static final String RESET = "reset";

    public static final String START = "START";
    public static final String END = "END";

    @BeforeClass
    public static void setup() throws ServletException {
        DeploymentUtils.setupServlet(new ServletExtension() {
            @Override
            public void handleDeployment(DeploymentInfo deploymentInfo, ServletContext servletContext) {
                deploymentInfo.setIgnoreFlush(false);
            }
        },
                new ServletInfo(BLOCKING_SERVLET, BlockingOutputStreamServlet.class)
                        .addMapping("/" + BLOCKING_SERVLET),
                new ServletInfo(ASYNC_SERVLET, AsyncOutputStreamServlet.class)
                        .addMapping("/" + ASYNC_SERVLET)
                        .setAsyncSupported(true),
                new ServletInfo(CONTENT_LENGTH_SERVLET, ContentLengthCloseFlushServlet.class)
                        .addMapping("/" + CONTENT_LENGTH_SERVLET),
                new ServletInfo(FLUSH_BUFFER_SERVLET, FlushBufferServlet.class)
                        .addMapping("/" + FLUSH_BUFFER_SERVLET)
                        .setAsyncSupported(true),
                new ServletInfo(RESET, ResetBufferServlet.class).addMapping("/" + RESET));
    }

    @Test
    public void testFlushBufferCommitsEmptyResponseAndAllowsSubsequentOutput() throws IOException {
        runFlushBufferTest(null, "true:body");
    }

    @Test
    public void testResetBufferFailsAfterFlushingEmptyResponse() throws IOException {
        runFlushBufferTest("resetBuffer", "true");
    }

    @Test
    public void testResetFailsWithoutChangingOutputModeAfterFlushingEmptyResponse() throws IOException {
        runFlushBufferTest("reset", "true:true");
    }

    @Test
    public void testAsyncFlushCommitsEmptyResponseAndRejectsResetBuffer() throws IOException {
        runFlushBufferTest("async", "true:true:body");
    }

    @Test
    public void testAsyncEmptyFlushDoesNotTriggerAnotherWriteCallback() throws IOException {
        runFlushBufferTest("asyncOpen", "");
    }

    @Test
    public void testAsyncEmptyAllocatedBufferFlushDoesNotTriggerAnotherWriteCallback() throws IOException {
        runFlushBufferTest("asyncOpenReset", "");
    }

    @Test
    public void testAsyncEmptyFlushSendsResponseHeadBeforeCompletion() throws Exception {
        assertAsyncEmptyFlushSendsResponseHeadBeforeCompletion("asyncHead");
    }

    @Test
    public void testAsyncEmptyNonBlockingFlushSendsResponseHeadBeforeCompletion() throws Exception {
        assertAsyncEmptyFlushSendsResponseHeadBeforeCompletion("asyncHeadNonBlocking");
    }

    private void assertAsyncEmptyFlushSendsResponseHeadBeforeCompletion(String action) throws Exception {
        FlushBufferServlet.AsyncHeadControl control = new FlushBufferServlet.AsyncHeadControl();
        FlushBufferServlet.asyncHeadControl = control;
        try {
            URI uri = URI.create(getBaseUrl());
            Socket socket = "https".equals(uri.getScheme())
                    ? DefaultServer.createClientSslContext().getSocketFactory().createSocket()
                    : new Socket();
            try (Socket response = socket) {
                response.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 3_000);
                response.setSoTimeout(3_000);
                response.getOutputStream().write(("GET /servletContext/" + FLUSH_BUFFER_SERVLET
                        + "?action=" + action + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                response.getOutputStream().flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getInputStream(), StandardCharsets.US_ASCII));
                Assert.assertEquals("HTTP/1.1 200 OK", reader.readLine());
                boolean foundHeader = false;
                boolean headComplete = false;
                for (int i = 0; i < 50; i++) {
                    String line = reader.readLine();
                    Assert.assertNotNull("Response head ended before the blank line", line);
                    if (line.isEmpty()) {
                        headComplete = true;
                        break;
                    }
                    if (line.equalsIgnoreCase("X-Async-Head: ready")) {
                        foundHeader = true;
                    }
                }
                Assert.assertTrue("Response head was not complete", headComplete);
                Assert.assertTrue("Response head marker was missing", foundHeader);
                Assert.assertEquals("Async context completed before the response head arrived", 1, control.completed.getCount());
                control.release.countDown();
                Assert.assertTrue("Async context did not complete", control.completed.await(5, TimeUnit.SECONDS));
            }
        } finally {
            control.release.countDown();
            FlushBufferServlet.asyncHeadControl = null;
        }
    }

    private void runFlushBufferTest(String action, String expectedBody) throws IOException {
        TestHttpClient client = createClient();
        try {
            String uri = getBaseUrl() + "/servletContext/" + FLUSH_BUFFER_SERVLET;
            if (action != null) {
                uri += "?action=" + action;
            }
            HttpGet get = new HttpGet(uri);
            HttpResponse result = client.execute(get);

            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals(expectedBody, HttpClientUtils.readResponse(result));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testFlushAndCloseWithContentLength() throws Exception {
        TestHttpClient client = createClient();
        try {
            String uri = getBaseUrl() + "/servletContext/" + CONTENT_LENGTH_SERVLET;

            HttpGet get = new HttpGet(uri);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("a", response);

            get = new HttpGet(uri);
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("OK", response);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    protected TestHttpClient createClient() {
        return new TestHttpClient();
    }


    @Test
    public void testResetBuffer() throws Exception {
        TestHttpClient client = createClient();
        try {
            String uri = getBaseUrl() + "/servletContext/" + RESET;

            HttpGet get = new HttpGet(uri);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("hello world", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testBlockingServletOutputStream() throws IOException {
        message = START +  HELLO_WORLD + END;
        runTest(message, BLOCKING_SERVLET, false, true, 1, true, false, false);

        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        builder.append(START);
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 1000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString() + END;
                runTest(message, BLOCKING_SERVLET, false, false, 1, false, false, false);
                runTest(message, BLOCKING_SERVLET, true, false, 10, false, false, false);
                runTest(message, BLOCKING_SERVLET, false, true, 3, false, false, false);
                runTest(message, BLOCKING_SERVLET, true, true, 7, false, false, false);
            } catch (Throwable e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }


    @Test
    public void testChunkedResponseWithInitialFlush() throws IOException {
        message = START + HELLO_WORLD + END;
        runTest(message, BLOCKING_SERVLET, false, true, 1, true, false, false);
    }

    @Test
    public void testAsyncServletOutputStream() {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        builder.append(START);
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString() + END;
                runTest(message, ASYNC_SERVLET, false, false, 1, false, false, false);
                runTest(message, ASYNC_SERVLET, true, false, 10, false, false, false);
                runTest(message, ASYNC_SERVLET, false, true, 3, false, false, false);
                runTest(message, ASYNC_SERVLET, true, true, 7, false, false, false);
            } catch (Exception e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletOutputStreamOffIOThread() {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        builder.append(START);
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString() + END;
                runTest(message, ASYNC_SERVLET, false, false, 1, false, false, true);
                runTest(message, ASYNC_SERVLET, true, false, 10, false, false, true);
                runTest(message, ASYNC_SERVLET, false, true, 3, false, false, true);
                runTest(message, ASYNC_SERVLET, true, true, 7, false, false, true);
            } catch (Exception e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletOutputStreamWithPreableOffIOThread() {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        builder.append(START);
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString() + END;
                runTest(message, ASYNC_SERVLET, false, false, 1, false, true, true);
                runTest(message, ASYNC_SERVLET, true, false, 10, false, true, true);
                runTest(message, ASYNC_SERVLET, false, true, 3, false, true, true);
                runTest(message, ASYNC_SERVLET, true, true, 7, false, true, true);
            } catch (Exception e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    @Test
    public void testAsyncServletOutputStreamWithPreable() {
        StringBuilder builder = new StringBuilder(1000 * HELLO_WORLD.length());
        builder.append(START);
        for (int i = 0; i < 10; ++i) {
            try {
                for (int j = 0; j < 10000; ++j) {
                    builder.append(HELLO_WORLD);
                }
                String message = builder.toString() + END;
                runTest(message, ASYNC_SERVLET, false, false, 1, false, true, false);
                runTest(message, ASYNC_SERVLET, true, false, 10, false, true, false);
                runTest(message, ASYNC_SERVLET, false, true, 3, false, true, false);
                runTest(message, ASYNC_SERVLET, true, true, 7, false, true, false);
            } catch (Exception e) {
                throw new RuntimeException("test failed with i equal to " + i, e);
            }
        }
    }

    public void runTest(final String message, String url, final boolean flush, final boolean close, int reps, boolean initialFlush, boolean writePreable, boolean offIoThread) throws IOException {
        TestHttpClient client = createClient();
        try {
            ServletOutputStreamTestCase.message = message;
            String uri = getBaseUrl() + "/servletContext/" + url + "?reps=" + reps + "&";
            if (flush) {
                uri = uri + "flush=true&";
            }
            if (close) {
                uri = uri + "close=true&";
            }
            if(initialFlush) {
                uri = uri + "initialFlush=true&";
            }
            if(writePreable) {
                uri = uri + "preamble=true&";
            }
            if(offIoThread) {
                uri += "offIoThread=true&";
            }
            HttpGet get = new HttpGet(uri);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            StringBuilder builder = new StringBuilder(reps * message.length());
            for (int j = 0; j < reps; ++j) {
                builder.append(message);
            }
            if(writePreable) {
                builder.append(builder.toString()); //content gets written twice in this case
            }
            final String response = HttpClientUtils.readResponse(result);
            String expected = builder.toString();
            Assert.assertTrue("Must start with START", response.startsWith(START));
            Assert.assertTrue("Must end with END", response.endsWith(END));
            Assert.assertEquals(expected.length(), response.length());
            Assert.assertEquals(expected, response);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    protected String getBaseUrl() {
        return DefaultServer.getDefaultServerURL();
    }

}
