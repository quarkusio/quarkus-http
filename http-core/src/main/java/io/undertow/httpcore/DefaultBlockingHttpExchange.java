package io.undertow.httpcore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DefaultBlockingHttpExchange implements BlockingHttpExchange {

    private InputStream inputStream;
    private OutputStream outputStream;
    final HttpExchange exchange;

    public DefaultBlockingHttpExchange(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public InputStream getInputStream() {
        if (inputStream == null) {
            inputStream = new UndertowInputStream(exchange);
        }
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        if (outputStream == null) {
            outputStream = new UndertowOutputStream(exchange);
        }
        return outputStream;
    }

    @Override
    public void close() throws IOException {
        if(outputStream != null) {
            outputStream.close();
        }
        if(inputStream != null) {
            inputStream.close();
        }
    }
}
