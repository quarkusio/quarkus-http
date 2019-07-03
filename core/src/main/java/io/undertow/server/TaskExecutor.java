package io.undertow.server;


import java.io.IOException;

import io.netty.channel.EventLoop;
import io.undertow.UndertowLogger;
import io.undertow.httpcore.IoCallback;

/**
 * Represents the task executor that maintains the '1 thread at a time' rules for Undertow, while also
 * preventing potentially infinite recursion when implementing callbacks.
 * <p>
 * Singly linked lists are used as it is not expected that this task list will ever grow to more that a couple of items
 * at most (usually 1).
 */
public class TaskExecutor {

    private volatile boolean executingHandlerChain;
    private volatile boolean executingTasks;


    final EventLoop eventLoop;

    /**
     * The first of the IO tasks. \
     */
    volatile Task ioTaskHead;

    /**
     * Tasks added by the current thread that owns the exchange
     * <p>
     * Accessed under lock.
     */
    volatile Task currentThreadHead;

    final Runnable runTasksInIoThreadRunnable;

    public TaskExecutor(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
        runTasksInIoThreadRunnable = new Runnable() {
            @Override
            public void run() {
                runTasks();
            }
        };
    }

    public boolean isExecutingHandlerChain() {
        return executingHandlerChain;
    }

    public void beginExecutingHandlerChain() {
        assert !executingHandlerChain;
        executingHandlerChain = true;
    }

    public void endExecutingHandlerChain() {
        assert executingHandlerChain;
        executingHandlerChain = false;
        if(!executingTasks) {
            if(eventLoop.inEventLoop()) {
                runTasks();
            } else {
                if(currentThreadHead == null) {

                }
            }
        }

    }

    public void execute(Runnable runnable, HttpServerExchange exchange) {
        Task newTask = new Task(runnable, null, null, exchange, null, null);
        queueTask(newTask);
    }

    public void execute(HttpHandler handler, HttpServerExchange exchange) {
        Task newTask = new Task(null, handler, null, exchange, null, null);
        queueTask(newTask);
    }

    public <T> void execute(IoCallback<T> callback, T context, HttpServerExchange exchange) {
        Task newTask = new Task(null, null, (IoCallback<Object>) callback, exchange, context, null);
        queueTask(newTask);
    }

    public <T> void execute(IoCallback<T> callback, T context, IOException exception, HttpServerExchange exchange) {
        Task newTask = new Task(null, null, (IoCallback<Object>) callback, exchange, context, exception);
        queueTask(newTask);
    }


    private void queueTask(Task newTask) {
        boolean ioThread = eventLoop.inEventLoop();
        if (ioThread) {
            if (this.ioTaskHead == null) {
                this.ioTaskHead = newTask;
            } else {
                Task current = this.ioTaskHead;
                while (current.next != null) {
                    current = current.next;
                }
                current.next = newTask;
            }
        } else {
            synchronized (this) {
                if (currentThreadHead == null) {
                    currentThreadHead = newTask;
                } else {
                    Task current = currentThreadHead;
                    while (current.next != null) {
                        current = current.next;
                    }
                    current.next = newTask;
                }
            }
        }
        if (!executingHandlerChain && !executingTasks) {
            if (ioThread) {
                runTasks();
            } else {
                //if this was a submission from a random thread
                //start event loop processing
                eventLoop.execute(runTasksInIoThreadRunnable);
            }
        }
    }

    private void runTasks() {
        assert eventLoop.inEventLoop();
        assert !executingTasks;
        executingTasks = true;
        try {
            //copy over any tasks added by other threads
            //when we are running the IO thread owns the exchange so nothing else should be added
            synchronized (this) {
                if (currentThreadHead != null) {
                    if (this.ioTaskHead == null) {
                        this.ioTaskHead = currentThreadHead;
                    } else {
                        Task current = this.ioTaskHead;
                        while (current.next != null) {
                            current = current.next;
                        }
                        current.next = current;
                    }
                    currentThreadHead = null;
                }
            }
            for (; ; ) {
                Task current = ioTaskHead;
                if (current == null) {
                    return;
                }
                try {
                    if (current.task != null) {
                        Connectors.executeRootHandler(new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                current.task.run();
                            }
                        }, current.exchange);
                    } else if (current.handler != null) {
                        Connectors.executeRootHandler(current.handler, current.exchange);
                    } else {
                        Connectors.executeRootHandler(new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                if (current.error == null) {
                                    current.callback.onComplete(exchange, current.context);
                                } else {
                                    current.callback.onException(current.exchange, current.context, current.error);
                                }
                            }
                        }, current.exchange);
                    }
                } catch (Throwable t) {
                    UndertowLogger.REQUEST_LOGGER.failedToRunTask(t);
                }
                ioTaskHead = current.next;
            }
        } finally {
            executingTasks = false;
        }

    }

    private static class Task {

        Task next;

        final Runnable task;

        final HttpHandler handler;

        final IoCallback<Object> callback;

        final HttpServerExchange exchange;

        final Object context;

        final IOException error;

        private Task(Runnable task, HttpHandler handler, IoCallback<Object> callback, HttpServerExchange exchange, Object context, IOException error) {
            this.task = task;
            this.handler = handler;
            this.callback = callback;
            this.exchange = exchange;
            this.context = context;
            this.error = error;
        }
    }

}
