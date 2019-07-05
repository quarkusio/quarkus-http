package io.undertow.httpcore;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;

public interface InputChannel {

    /**
     * Can only be called is isReadable has returned true.
     *
     * If there is data this will return a buffer, otherwise it will return null
     * to indicate EOF
     *
     */
    ByteBuf readAsync() throws IOException;

    /**
     * Must be called before calling {@link #readAsync()}. If this returns true then data can be read,
     * if this returns false then a read handler can be registered using {@link #setReadHandler(BiConsumer, Object)}
     *
     * @return <code>true</code> if data can be read
     */
    boolean isReadable();

    /**
     * Registers a read handler that will be called when the channel is readable.
     *
     * This handler will only be called once, to continue to read this method must be
     * called every time {@link #isReadable()} returns false
     * @param handler The handler
     * @param context A context object that will be passed to the handler
     * @param <T> The type of the context object
     */
    <T>  void setReadHandler(BiConsumer<InputChannel, T> handler, T context);

    /**
     *
     * @return The number of bytes that are available to be immediately read
     */
    int readBytesAvailable();

    /**
     * Reads some data. If all data has been read it will return null.
     *
     * @return
     * @throws IOException on failure
     */
    ByteBuf readBlocking() throws IOException;
}
