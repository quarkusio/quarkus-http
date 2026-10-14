/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the License is
 * distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.undertow.server.handlers;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.httpcore.StatusCodes;
import io.undertow.server.handlers.resource.URLResource;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;

@RunWith(DefaultServer.class)
public class URLResourceBlockingRangeTestCase {

    private static final int FILE_SIZE = 128 * 1024;
    private static final int RANGE_START = 1000;

    private static byte[] fileContent;
    private static Path filePath;

    private static final AtomicReference<byte[]> servedBytes =
            new AtomicReference<>();

    @BeforeClass
    public static void setup() throws Exception {
        filePath = Files.createTempFile(
                "undertow-blocking-range-", ".bin");

        fileContent = new byte[FILE_SIZE];

        for (int i = 0; i < FILE_SIZE; i++) {
            fileContent[i] = (byte) ((i * 31 + 7) & 0xff);
        }

        Files.write(filePath, fileContent);

        URL resourceUrl = filePath.toUri().toURL();

        DefaultServer.setRootHandler(
                new BlockingHandler(exchange -> {

                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    URLResource resource =
                            new URLResource(resourceUrl, "/range.bin");

                    // Explicitly exercise the buggy blocking method.
                    resource.serveRangeBlocking(
                            output,
                            exchange,
                            RANGE_START,
                            FILE_SIZE - 1);

                    servedBytes.set(output.toByteArray());

                    exchange.endExchange();
                }));
    }

    @AfterClass
    public static void cleanup() throws Exception {
        Files.deleteIfExists(filePath);
    }

    @Test
    public void testBlockingRangeReturnsExactBytes()
            throws Exception {

        servedBytes.set(null);

        TestHttpClient client = new TestHttpClient();

        try {
            HttpGet request = new HttpGet(
                    DefaultServer.getDefaultServerURL()
                            + "/blocking-range");

            HttpResponse response = client.execute(request);

            Assert.assertEquals(
                    StatusCodes.OK,
                    response.getStatusLine().getStatusCode());

            byte[] actual = servedBytes.get();

            Assert.assertNotNull(
                    "Blocking range method did not complete",
                    actual);

            byte[] expected = Arrays.copyOfRange(
                    fileContent,
                    RANGE_START,
                    FILE_SIZE);

            Assert.assertEquals(
                    expected.length,
                    actual.length);

            Assert.assertArrayEquals(expected, actual);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
