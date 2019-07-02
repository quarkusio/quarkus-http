package io.undertow.iocore;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public interface OutputChannel extends AutoCloseable {

    /**
     * Writes the given UTF-8 data and ends the exchange
     *
     * @param data The data to write
     */
    default void writeAsync(String data) {
        writeAsync(Unpooled.copiedBuffer(data, StandardCharsets.UTF_8), true, IoCallback.END_EXCHANGE, null);
    }

    /**
     * Writes the given  data in the provided charset and ends the exchange
     *
     * @param data The data to write
     */
    default void writeAsync(String data, Charset charset) {
        writeAsync(Unpooled.copiedBuffer(data, charset), true, IoCallback.END_EXCHANGE, null);
    }

    /**
     * Writes the given data in the provided charset and invokes the provided callback on completion
     *
     * @param data The data to write
     */
    default <T> void writeAsync(String data, Charset charset, boolean last, IoCallback<T> callback, T context) {
        writeAsync(Unpooled.copiedBuffer(data, charset), last, callback, context);
    }

    <T> void writeAsync(ByteBuf data, boolean last, IoCallback<T> callback, T context);

    /**
     *
     * @param data
     * @param last
     * @throws IOException
     */
    void writeBlocking(ByteBuf data, boolean last) throws IOException;
}
