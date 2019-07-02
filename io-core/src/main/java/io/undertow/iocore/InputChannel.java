package io.undertow.iocore;

import java.io.IOException;

import io.netty.buffer.ByteBuf;

public interface InputChannel {

    void readAsync(IoCallback<ByteBuf> cb);

    int readBytesAvailable();

    /**
     * Reads some data. If all data has been read it will return null.
     *
     * @return
     * @throws IOException on failure
     */
    ByteBuf readBlocking() throws IOException;
}
