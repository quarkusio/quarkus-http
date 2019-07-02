package io.undertow.iocore;

public interface HttpExchange extends OutputChannel, InputChannel {

    HttpExchange endExchange();

    HttpExchange setStatusCode(int code);

    int getStatusCode();

    boolean isInIoThread();
}
