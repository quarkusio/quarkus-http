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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class FlushBufferServlet extends HttpServlet {

    static final class AsyncHeadControl {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean completing = new AtomicBoolean();
    }

    static volatile AsyncHeadControl asyncHeadControl;

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        if ("resetBuffer".equals(action)) {
            resetBuffer(resp);
        } else if ("reset".equals(action)) {
            reset(resp);
        } else if ("async".equals(action)) {
            asyncFlush(req, resp);
        } else if ("asyncOpen".equals(action)) {
            asyncOpenFlush(req, resp, false);
        } else if ("asyncOpenReset".equals(action)) {
            asyncOpenFlush(req, resp, true);
        } else if ("asyncHead".equals(action)) {
            asyncHead(req, resp, false);
        } else if ("asyncHeadNonBlocking".equals(action)) {
            asyncHead(req, resp, true);
        } else {
            flushAndWrite(resp);
        }
    }

    private void flushAndWrite(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();
        boolean committed = resp.isCommitted();
        resp.flushBuffer();
        output.print(committed + ":body");
        output.close();
    }

    private void resetBuffer(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();

        boolean resetRejected = false;
        try {
            resp.resetBuffer();
        } catch (IllegalStateException expected) {
            resetRejected = true;
        }

        output.print(Boolean.toString(resetRejected));
        output.close();
    }

    private void reset(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();

        boolean resetRejected = false;
        try {
            resp.reset();
        } catch (IllegalStateException expected) {
            resetRejected = true;
        }

        PrintWriter writer = null;
        boolean writerRejected = false;
        try {
            writer = resp.getWriter();
        } catch (IllegalStateException expected) {
            writerRejected = true;
        }

        String result = resetRejected + ":" + writerRejected;
        if (writer == null) {
            output.print(result);
            output.close();
        } else {
            writer.print(result);
            writer.close();
        }
    }

    private void asyncFlush(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AsyncContext context = req.startAsync();
        ServletOutputStream output = resp.getOutputStream();
        output.setWriteListener(new WriteListener() {
            private boolean finished;

            @Override
            public void onWritePossible() throws IOException {
                if (finished || !output.isReady()) {
                    return;
                }
                finished = true;

                output.flush();
                boolean committed = resp.isCommitted();
                boolean resetRejected = false;
                try {
                    resp.resetBuffer();
                } catch (IllegalStateException expected) {
                    resetRejected = true;
                }
                output.flush();
                output.print(committed + ":" + resetRejected + ":body");
                output.close();
                context.complete();
            }

            @Override
            public void onError(Throwable throwable) {
                context.complete();
            }
        });
    }

    private void asyncOpenFlush(HttpServletRequest req, HttpServletResponse resp, boolean resetBufferBeforeFlush) throws IOException {
        AsyncContext context = req.startAsync();
        ServletOutputStream output = resp.getOutputStream();
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicBoolean completionStarted = new AtomicBoolean();
        output.setWriteListener(new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {
                if (!output.isReady() || completionStarted.get()) {
                    return;
                }
                if (callbackCount.incrementAndGet() == 1) {
                    if (resetBufferBeforeFlush) {
                        output.print("discarded");
                        resp.resetBuffer();
                    }
                    output.flush();
                    context.start(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            if (completionStarted.compareAndSet(false, true)) {
                                context.complete();
                            }
                        }
                    });
                } else if (completionStarted.compareAndSet(false, true)) {
                    output.print("unexpected write callback");
                    output.close();
                    context.complete();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (completionStarted.compareAndSet(false, true)) {
                    context.complete();
                }
            }
        });
    }

    private void asyncHead(HttpServletRequest req, HttpServletResponse resp, boolean nonBlocking) throws IOException {
        AsyncHeadControl control = asyncHeadControl;
        if (control == null) {
            throw new IllegalStateException("No async response head control configured");
        }
        AsyncContext context = req.startAsync();
        context.setTimeout(15_000);
        resp.setHeader("X-Async-Head", "ready");
        if (nonBlocking) {
            ServletOutputStream output = resp.getOutputStream();
            output.setWriteListener(new WriteListener() {
                private boolean flushed;

                @Override
                public void onWritePossible() throws IOException {
                    if (flushed || !output.isReady()) {
                        return;
                    }
                    flushed = true;
                    output.flush();
                    completeWhenReleased(context, control);
                }

                @Override
                public void onError(Throwable throwable) {
                    completeAsyncHead(context, control);
                }
            });
        } else {
            resp.flushBuffer();
            completeWhenReleased(context, control);
        }
    }

    private void completeWhenReleased(AsyncContext context, AsyncHeadControl control) {
        context.start(() -> {
            try {
                control.release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completeAsyncHead(context, control);
            }
        });
    }

    private void completeAsyncHead(AsyncContext context, AsyncHeadControl control) {
        if (control.completing.compareAndSet(false, true)) {
            try {
                context.complete();
            } finally {
                control.completed.countDown();
            }
        }
    }
}
