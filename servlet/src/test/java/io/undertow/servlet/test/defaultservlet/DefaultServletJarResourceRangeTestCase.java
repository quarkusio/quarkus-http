/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2026 Red Hat, Inc., and individual contributors
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

package io.undertow.servlet.test.defaultservlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import jakarta.servlet.ServletException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.StatusCodes;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.handlers.DefaultServlet;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;

/**
 * Range requests for a resource inside a jar, where the stream returns reads of varying size.
 */
@RunWith(DefaultServer.class)
public class DefaultServletJarResourceRangeTestCase {

    private static byte[] content;
    private static Path jar;
    private static URLClassLoader jarClassLoader;

    @BeforeClass
    public static void setup() throws ServletException, IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 0; text.length() < 1024 * 1024; line++) {
            text.append(String.format("%08d lorem ipsum dolor sit amet, consectetur adipiscing elit%n", line));
        }
        content = text.toString().getBytes(StandardCharsets.UTF_8);

        jar = Files.createTempFile("range-resources", ".jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("META-INF/resources/big.txt"));
            jarOut.write(content);
            jarOut.closeEntry();
        }
        jarClassLoader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

        DeploymentInfo builder = new DeploymentInfo()
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setClassLoader(DefaultServletJarResourceRangeTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .setDeploymentName("servletContext.war")
                .setResourceManager(new ClassPathResourceManager(jarClassLoader, "META-INF/resources"));
        builder.addServlet(new ServletInfo("default", DefaultServlet.class).addMapping("/*"));

        DeploymentManager manager = ServletContainer.Factory.newInstance().addDeployment(builder);
        manager.deploy();
        PathHandler root = new PathHandler();
        root.addPrefixPath(builder.getContextPath(), manager.start());
        DefaultServer.setRootHandler(root);
    }

    @AfterClass
    public static void cleanup() throws IOException {
        jarClassLoader.close();
        Files.deleteIfExists(jar);
    }

    @Test
    public void testRangeToTheEnd() throws IOException {
        assertRange("bytes=100000-", 100000, content.length - 1);
    }

    @Test
    public void testRangeInTheMiddle() throws IOException {
        assertRange("bytes=100000-200000", 100000, 200000);
    }

    @Test
    public void testRangeWithinTheFirstRead() throws IOException {
        assertRange("bytes=5-10", 5, 10);
    }

    @Test
    public void testSuffixRange() throws IOException {
        assertRange("bytes=-5000", content.length - 5000, content.length - 1);
    }

    private static void assertRange(String range, int start, int end) throws IOException {
        try (TestHttpClient client = new TestHttpClient()) {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/big.txt");
            get.addHeader(HttpHeaderNames.RANGE, range);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            Assert.assertEquals("bytes " + start + "-" + end + "/" + content.length,
                    result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());
            Assert.assertArrayEquals(Arrays.copyOfRange(content, start, end + 1),
                    EntityUtils.toByteArray(result.getEntity()));
        }
    }
}
