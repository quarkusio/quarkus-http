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

import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Starts a never completed async cycle, the way a Server-Sent Events endpoint does, and records the
 * {@link AsyncListener} callbacks the container delivers.
 */
public class AsyncClientDisconnectServlet extends HttpServlet {

    public static final BlockingDeque<String> EVENTS = new LinkedBlockingDeque<>();

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/event-stream");
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(0);
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                EVENTS.add("onComplete");
            }

            @Override
            public void onError(AsyncEvent event) {
                EVENTS.add("onError");
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                EVENTS.add("onTimeout");
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });
        resp.getOutputStream().write(":\n\n".getBytes()); // Work around until quarkusio/quarkus#55730 is fixed.
        resp.getOutputStream().flush();
    }
}
