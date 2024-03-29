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

package io.undertow.servlet.test.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import jakarta.servlet.ServletException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.util.DeploymentUtils;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpOneOnly;

/**
 * @author Stuart Douglas
 */
@HttpOneOnly
@RunWith(DefaultServer.class)
@Ignore
public class SimpleUpgradeTestCase {

    @BeforeClass
    public static void setup() throws ServletException {

        DeploymentUtils.setupServlet(
                new ServletInfo("upgradeServlet", UpgradeServlet.class)
                        .addMapping("/upgrade"),
                new ServletInfo("upgradeAsyncServlet", AsyncUpgradeServlet.class)
                        .addMapping("/asyncupgrade"));
    }

    @Test
    public void testBlockingUpgrade() throws IOException {
        runTest("/servletContext/upgrade");
    }

    @Test
    public void testAsyncUpgrade() throws IOException {
        runTest("/servletContext/asyncupgrade");
    }

    public void runTest(final String url) throws IOException {
        final Socket socket = new Socket(DefaultServer.getHostAddress("default"), DefaultServer.getHostPort("default"));

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + url + " HTTP/1.1\r\nHost:default\r\nConnection: upgrade\r\nUpgrade: servlet\r\n\r\n").getBytes());
        out.flush();
        Assert.assertTrue(readBytes(in).startsWith("HTTP/1.1 101 Switching Protocols\r\n"));

        out.write("Echo Messages\r\n\r\n".getBytes());
        out.flush();
        Assert.assertEquals("Echo Messages\r\n\r\n", readBytes(in));

        out.write("Echo Messages2\r\n\r\n".getBytes());
        out.flush();
        Assert.assertEquals("Echo Messages2\r\n\r\n", readBytes(in));

        out.write("exit\r\n\r\n".getBytes());
        out.flush();
        out.close();

    }

    private String readBytes(final InputStream in) throws IOException {
        final StringBuilder builder = new StringBuilder();
        byte[] buf = new byte[100];
        int read;
        while (!builder.toString().contains("\r\n\r\n") && (read = in.read(buf)) != -1) { //awesome hack
            builder.append(new String(buf, 0, read));
        }
        return builder.toString();
    }

}
