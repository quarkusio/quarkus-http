/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026 Red Hat, Inc., and individual contributors
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
package io.undertow.server.handlers;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.StatusCodes;
import io.undertow.server.handlers.resource.URLResource;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;

@RunWith(DefaultServer.class)
public class URLResourceJarRangeTestCase {

    private static final int FILE_SIZE = 1024 * 1024 + 4;
    private static byte[] content;
    private static URL jarUrl;

    @BeforeClass
    public static void setup() throws Exception {
        content = new byte[FILE_SIZE];
        new Random(57175L).nextBytes(content);

        Path jarPath = Files.createTempFile(
                "undertow-range-", ".jar");
        jarPath.toFile().deleteOnExit();

        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry(
                    "META-INF/resources/big.bin"));
            jar.write(content);
            jar.closeEntry();
        }

        jarUrl = new URL("jar:"
                + jarPath.toUri().toURL().toExternalForm()
                + "!/META-INF/resources/big.bin");

        DefaultServer.setRootHandler(
                new BlockingHandler(exchange -> {
                    String range = exchange.getRequestHeader(
                            HttpHeaderNames.RANGE);

                    if (range == null
                            || !range.startsWith("bytes=")) {
                        exchange.setStatusCode(
                                StatusCodes.BAD_REQUEST);
                        exchange.endExchange();
                        return;
                    }

                    String value = range.substring(6);
                    int dash = value.indexOf('-');

                    long start = Long.parseLong(
                            value.substring(0, dash));
                    long end = dash == value.length() - 1
                            ? FILE_SIZE - 1
                            : Long.parseLong(
                                    value.substring(dash + 1));

                    exchange.setStatusCode(
                            StatusCodes.PARTIAL_CONTENT);
                    exchange.setResponseHeader(
                            HttpHeaderNames.CONTENT_RANGE,
                            "bytes " + start + "-" + end
                                    + "/" + FILE_SIZE);
                    exchange.setResponseContentLength(
                            end - start + 1);

                    new URLResource(jarUrl, "/big.bin")
                            .serveRangeBlocking(
                                    exchange.getOutputStream(),
                                    exchange, start, end);

                    exchange.endExchange();
                }));
    }

    @Test
    public void testLargeJarRange() throws Exception {
        assertRange(100000, FILE_SIZE - 1);
    }

    @Test
    public void testShortJarRange() throws Exception {
        assertRange(100000, 100031);
    }

    @Test
    public void testBufferBoundary() throws Exception {
        assertRange(8192, 8192);
    }

    @Test
    public void testFirstByte() throws Exception {
        assertRange(0, 0);
    }

    @Test
    public void testLastByte() throws Exception {
        assertRange(FILE_SIZE - 1, FILE_SIZE - 1);
    }

    private void assertRange(int start, int end)
            throws Exception {

        TestHttpClient client = new TestHttpClient();

        try {
            HttpGet request = new HttpGet(
                    DefaultServer.getDefaultServerURL()
                            + "/big.bin");

            request.addHeader(
                    HttpHeaderNames.RANGE,
                    "bytes=" + start + "-" + end);

            HttpResponse response = client.execute(request);

            Assert.assertEquals(
                    StatusCodes.PARTIAL_CONTENT,
                    response.getStatusLine().getStatusCode());

            Assert.assertEquals(
                    "bytes " + start + "-" + end
                            + "/" + FILE_SIZE,
                    response.getFirstHeader(
                            HttpHeaderNames.CONTENT_RANGE)
                            .getValue());

            byte[] actual = EntityUtils.toByteArray(
                    response.getEntity());

            byte[] expected = Arrays.copyOfRange(
                    content, start, end + 1);

            Assert.assertEquals(
                    expected.length, actual.length);

            Assert.assertArrayEquals(expected, actual);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
