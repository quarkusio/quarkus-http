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

package io.undertow.servlet.test.async;

import static io.undertow.servlet.Servlets.servlet;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.servlet.test.util.DeploymentUtils;
import io.undertow.testutils.DefaultServer;

/**
 * @author Bauke Scholtz
 */
@RunWith(DefaultServer.class)
public class AsyncClientDisconnectTestCase {

    private static final int TIMEOUT = 10;

    @BeforeClass
    public static void setup() throws ServletException {
        DeploymentUtils.setupServlet(servlet("asyncClientDisconnect", AsyncClientDisconnectServlet.class)
                .setAsyncSupported(true)
                .addMapping("/async-client-disconnect"));
    }

    @Before
    public void clearEvents() {
        AsyncClientDisconnectServlet.EVENTS.clear();
    }

    /**
     * When the client aborts a response of a never completed async cycle, the container must terminate the async cycle
     * and notify the listener, so that the application can release any per connection state.
     */
    @Test
    public void testClientDisconnectNotifiesAsyncListener() throws IOException, InterruptedException {
        try (Socket socket = new Socket(DefaultServer.getHostAddress(), DefaultServer.getHostPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /servletContext/async-client-disconnect HTTP/1.1\r\nHost: default\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            Assert.assertTrue("Response was not started", in.read() != -1);
        }

        Assert.assertEquals("onError", AsyncClientDisconnectServlet.EVENTS.poll(TIMEOUT, SECONDS));
        Assert.assertEquals("onComplete", AsyncClientDisconnectServlet.EVENTS.poll(TIMEOUT, SECONDS));
    }
}
