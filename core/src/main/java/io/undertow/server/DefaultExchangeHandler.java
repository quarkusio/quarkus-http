package io.undertow.server;

import io.undertow.httpcore.ExchangeHandler;
import io.undertow.httpcore.HttpExchange;

public class DefaultExchangeHandler implements ExchangeHandler {

    private final HttpHandler handler;

    public DefaultExchangeHandler(HttpHandler handler) {
        this.handler = handler;
    }

    @Override
    public void handle(HttpExchange delegate) {
        HttpServerExchange exchange = new HttpServerExchange(delegate, -1);
        Connectors.setExchangeRequestPath(exchange, delegate.getRequestURI(), "UTF-8", true, false, new StringBuilder());
        Connectors.executeRootHandler(handler, exchange);
    }
}
