package io.undertow.vertx;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.Arguments;
import io.vertx.core.internal.buffer.BufferInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;


public class VertxBufferImpl implements Buffer, BufferInternal {

  private ByteBuf buffer;

  public VertxBufferImpl(ByteBuf buffer) {
    this.buffer = buffer;
  }


  public String toString() {
    return buffer.toString(StandardCharsets.UTF_8);
  }

  public String toString(String enc) {
    return buffer.toString(Charset.forName(enc));
  }

  public String toString(Charset enc) {
    return buffer.toString(enc);
  }


  @Override
  public JsonObject toJsonObject() {
    return new JsonObject(this);
  }

  @Override
  public JsonArray toJsonArray() {
    return new JsonArray(this);
  }

  public byte getByte(int pos) {
    return buffer.getByte(pos);
  }

  public short getUnsignedByte(int pos) {
    return buffer.getUnsignedByte(pos);
  }

  public int getInt(int pos) {
    return buffer.getInt(pos);
  }

  public int getIntLE(int pos) {
    return buffer.getIntLE(pos);
  }

  public long getUnsignedInt(int pos) {
    return buffer.getUnsignedInt(pos);
  }

  public long getUnsignedIntLE(int pos) {
    return buffer.getUnsignedIntLE(pos);
  }

  public long getLong(int pos) {
    return buffer.getLong(pos);
  }

  public long getLongLE(int pos) {
    return buffer.getLongLE(pos);
  }

  public double getDouble(int pos) {
    return buffer.getDouble(pos);
  }

    @Override
    public double getDoubleLE(int i) {
        return buffer.getDoubleLE(i);
    }

    public float getFloat(int pos) {
    return buffer.getFloat(pos);
  }

    @Override
    public float getFloatLE(int i) {
        return buffer.getFloatLE(i);
    }

    public short getShort(int pos) {
    return buffer.getShort(pos);
  }

  public short getShortLE(int pos) {
    return buffer.getShortLE(pos);
  }

  public int getUnsignedShort(int pos) {
    return buffer.getUnsignedShort(pos);
  }

  public int getUnsignedShortLE(int pos) {
    return buffer.getUnsignedShortLE(pos);
  }

  public int getMedium(int pos) {
    return buffer.getMedium(pos);
  }

  public int getMediumLE(int pos) {
    return buffer.getMediumLE(pos);
  }

  public int getUnsignedMedium(int pos) {
    return buffer.getUnsignedMedium(pos);
  }

  public int getUnsignedMediumLE(int pos) {
    return buffer.getUnsignedMediumLE(pos);
  }

  public byte[] getBytes() {
    byte[] arr = new byte[buffer.writerIndex()];
    buffer.getBytes(0, arr);
    return arr;
  }

  public byte[] getBytes(int start, int end) {
    Arguments.require(end >= start, "end must be greater or equal than start");
    byte[] arr = new byte[end - start];
    buffer.getBytes(start, arr, 0, end - start);
    return arr;
  }

  @Override
  public Buffer getBytes(byte[] dst) {
    return getBytes(dst, 0);
  }

  @Override
  public Buffer getBytes(byte[] dst, int dstIndex) {
    return getBytes(0, buffer.writerIndex(), dst, dstIndex);
  }

  @Override
  public Buffer getBytes(int start, int end, byte[] dst) {
    return getBytes(start, end, dst, 0);
  }

  @Override
  public Buffer getBytes(int start, int end, byte[] dst, int dstIndex) {
    Arguments.require(end >= start, "end must be greater or equal than start");
    buffer.getBytes(start, dst, dstIndex, end - start);
    return this;
  }

  public Buffer getBuffer(int start, int end) {
    return new VertxBufferImpl(buffer.slice(start, end));
  }

  public String getString(int start, int end, String enc) {
    byte[] bytes = getBytes(start, end);
    Charset cs = Charset.forName(enc);
    return new String(bytes, cs);
  }

  public String getString(int start, int end) {
    byte[] bytes = getBytes(start, end);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public BufferInternal appendBuffer(Buffer buff) {
    buffer.writeBytes(buff.getBytes());
    return this;
  }

  public BufferInternal appendBuffer(Buffer buff, int offset, int len) {
    byte[] bytes = buff.getBytes();
    buffer.writeBytes(bytes, offset, len);
    return this;
  }

  public BufferInternal appendBytes(byte[] bytes) {
    buffer.writeBytes(bytes);
    return this;
  }

  public BufferInternal appendBytes(byte[] bytes, int offset, int len) {
    buffer.writeBytes(bytes, offset, len);
    return this;
  }

  public BufferInternal appendByte(byte b) {
    buffer.writeByte(b);
    return this;
  }

  public BufferInternal appendUnsignedByte(short b) {
    buffer.writeByte(b);
    return this;
  }

  public BufferInternal appendInt(int i) {
    buffer.writeInt(i);
    return this;
  }

  public BufferInternal appendIntLE(int i) {
    buffer.writeIntLE(i);
    return this;
  }

  public BufferInternal appendUnsignedInt(long i) {
    buffer.writeInt((int) i);
    return this;
  }

  public BufferInternal appendUnsignedIntLE(long i) {
    buffer.writeIntLE((int) i);
    return this;
  }

  public BufferInternal appendMedium(int i) {
    buffer.writeMedium(i);
    return this;
  }

  public BufferInternal appendMediumLE(int i) {
    buffer.writeMediumLE(i);
    return this;
  }

  public BufferInternal appendLong(long l) {
    buffer.writeLong(l);
    return this;
  }

  public BufferInternal appendLongLE(long l) {
    buffer.writeLongLE(l);
    return this;
  }

  public BufferInternal appendShort(short s) {
    buffer.writeShort(s);
    return this;
  }

  public BufferInternal appendShortLE(short s) {
    buffer.writeShortLE(s);
    return this;
  }

  public BufferInternal appendUnsignedShort(int s) {
    buffer.writeShort(s);
    return this;
  }

  public BufferInternal appendUnsignedShortLE(int s) {
    buffer.writeShortLE(s);
    return this;
  }

  public BufferInternal appendFloat(float f) {
    buffer.writeFloat(f);
    return this;
  }

    @Override
    public BufferInternal appendFloatLE(float v) {
        buffer.writeFloatLE(v);
        return this;
    }

    public BufferInternal appendDouble(double d) {
    buffer.writeDouble(d);
    return this;
  }

    @Override
    public BufferInternal appendDoubleLE(double v) {
        buffer.writeDoubleLE(v);
        return this;
    }

    public BufferInternal appendString(String str, String enc) {
    return append(str, Charset.forName(Objects.requireNonNull(enc)));
  }

  public BufferInternal appendString(String str) {
    return append(str, CharsetUtil.UTF_8);
  }

  public BufferInternal setByte(int pos, byte b) {
    ensureWritable(pos, 1);
    buffer.setByte(pos, b);
    return this;
  }

  public BufferInternal setUnsignedByte(int pos, short b) {
    ensureWritable(pos, 1);
    buffer.setByte(pos, b);
    return this;
  }

  public BufferInternal setInt(int pos, int i) {
    ensureWritable(pos, 4);
    buffer.setInt(pos, i);
    return this;
  }

  public BufferInternal setIntLE(int pos, int i) {
    ensureWritable(pos, 4);
    buffer.setIntLE(pos, i);
    return this;
  }

  public BufferInternal setUnsignedInt(int pos, long i) {
    ensureWritable(pos, 4);
    buffer.setInt(pos, (int) i);
    return this;
  }

  public BufferInternal setUnsignedIntLE(int pos, long i) {
    ensureWritable(pos, 4);
    buffer.setIntLE(pos, (int) i);
    return this;
  }

  public BufferInternal setMedium(int pos, int i) {
    ensureWritable(pos, 3);
    buffer.setMedium(pos, i);
    return this;
  }

  public BufferInternal setMediumLE(int pos, int i) {
    ensureWritable(pos, 3);
    buffer.setMediumLE(pos, i);
    return this;
  }

  public BufferInternal setLong(int pos, long l) {
    ensureWritable(pos, 8);
    buffer.setLong(pos, l);
    return this;
  }

  public BufferInternal setLongLE(int pos, long l) {
    ensureWritable(pos, 8);
    buffer.setLongLE(pos, l);
    return this;
  }

  public BufferInternal setDouble(int pos, double d) {
    ensureWritable(pos, 8);
    buffer.setDouble(pos, d);
    return this;
  }

    @Override
    public BufferInternal setDoubleLE(int pos, double v) {
        ensureWritable(pos, 8);
        buffer.setDoubleLE(pos, v);
        return this;
    }

    public BufferInternal setFloat(int pos, float f) {
    ensureWritable(pos, 4);
    buffer.setFloat(pos, f);
    return this;
  }

    @Override
    public BufferInternal setFloatLE(int pos, float v) {
        ensureWritable(pos, 4);
        buffer.setFloatLE(pos, v);
        return this;
    }

    public BufferInternal setShort(int pos, short s) {
    ensureWritable(pos, 2);
    buffer.setShort(pos, s);
    return this;
  }

  public BufferInternal setShortLE(int pos, short s) {
    ensureWritable(pos, 2);
    buffer.setShortLE(pos, s);
    return this;
  }

  public BufferInternal setUnsignedShort(int pos, int s) {
    ensureWritable(pos, 2);
    buffer.setShort(pos, s);
    return this;
  }

  public BufferInternal setUnsignedShortLE(int pos, int s) {
    ensureWritable(pos, 2);
    buffer.setShortLE(pos, s);
    return this;
  }

  public BufferInternal setBuffer(int pos, Buffer b) {
    ensureWritable(pos, b.length());
    buffer.setBytes(pos, b.getBytes());
    return this;
  }

  public BufferInternal setBuffer(int pos, Buffer b, int offset, int len) {
    ensureWritable(pos, len);
    var byteBuf = b.getBytes();
    buffer.setBytes(pos, byteBuf, offset, len);
    return this;
  }

  public VertxBufferImpl setBytes(int pos, ByteBuffer b) {
    ensureWritable(pos, b.limit());
    buffer.setBytes(pos, b);
    return this;
  }

  public BufferInternal setBytes(int pos, byte[] b) {
    ensureWritable(pos, b.length);
    buffer.setBytes(pos, b);
    return this;
  }

  public BufferInternal setBytes(int pos, byte[] b, int offset, int len) {
    ensureWritable(pos, len);
    buffer.setBytes(pos, b, offset, len);
    return this;
  }

  public BufferInternal setString(int pos, String str) {
    return setBytes(pos, str, CharsetUtil.UTF_8);
  }

  public BufferInternal setString(int pos, String str, String enc) {
    return setBytes(pos, str, Charset.forName(enc));
  }

  public int length() {
    return buffer.writerIndex();
  }

  public BufferInternal copy() {
    return new VertxBufferImpl(buffer.copy());
  }

  public BufferInternal slice() {
    return new VertxBufferImpl(buffer.slice());
  }

  public BufferInternal slice(int start, int end) {
    return new VertxBufferImpl(buffer.slice(start, end - start));
  }

  public ByteBuf getByteBuf() {
    // Return a duplicate so the Buffer can be written multiple times.
    // See #648
    return buffer;
  }

  private BufferInternal append(String str, Charset charset) {
    byte[] bytes = str.getBytes(charset);
    buffer.writeBytes(bytes);
    return this;
  }

  private BufferInternal setBytes(int pos, String str, Charset charset) {
    byte[] bytes = str.getBytes(charset);
    ensureWritable(pos, bytes.length);
    buffer.setBytes(pos, bytes);
    return this;
  }

  private void ensureWritable(int pos, int len) {
    int ni = pos + len;
    int cap = buffer.capacity();
    int over = ni - cap;
    if (over > 0) {
      buffer.writerIndex(cap);
      buffer.ensureWritable(over);
    }
    //We have to make sure that the writerindex is always positioned on the last bit of data set in the buffer
    if (ni > buffer.writerIndex()) {
      buffer.writerIndex(ni);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    VertxBufferImpl buffer1 = (VertxBufferImpl) o;
    return buffer != null ? buffer.equals(buffer1.buffer) : buffer1.buffer == null;
  }

  @Override
  public int hashCode() {
    return buffer != null ? buffer.hashCode() : 0;
  }

  @Override
  public void writeToBuffer(Buffer buff) {
    buff.appendInt(this.length());
    buff.appendBuffer(this);
  }

  @Override
  public int readFromBuffer(int pos, Buffer buffer) {
    int len = buffer.getInt(pos);
    Buffer b = buffer.getBuffer(pos + 4, pos + 4 + len);
    this.buffer = Unpooled.copiedBuffer(b.getBytes());
    return pos + 4 + len;
  }
}