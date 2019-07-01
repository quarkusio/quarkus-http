package io.undertow.server;

import io.netty.buffer.ByteBuf;

/**
 * Function that is called before a write is performed.
 */
public interface WriteFunction {

    ByteBuf preWrite(ByteBuf data, boolean last);
}
