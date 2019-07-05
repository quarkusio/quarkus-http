package io.undertow.httpcore;

import java.io.Closeable;
import java.util.concurrent.Executor;

public interface UndertowEngine {

    EngineInstance start(int ioThreads, Executor blockingExecutor, BufferAllocator bufferAllocator);

    void bindHttp(EngineInstance instance, ExchangeHandler handler, int port, String host, Object options);

    void bindHttps(EngineInstance instance, ExchangeHandler handler, int port, String host, String keyStore, String keyStorePassword, String trustStore, String trustStorePassword, Object options);

    interface EngineInstance extends Closeable {
        void close();
    }
}
