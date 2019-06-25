/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.servlet.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import io.undertow.server.HttpServerExchange;

/**
 * A sender that uses a print writer.
 * <p>
 * In general this should never be used. It exists for the edge case where a filter has called
 * getWriter() and then the default servlet is being used to serve a text file.
 *
 * @author Stuart Douglas
 */
public class WriterOutputStream extends OutputStream {

    /**
     * TODO: we should be used pooled buffers
     */
    public static final int BUFFER_SIZE = 128;

    private final Charset charset;
    private final HttpServerExchange exchange;
    private final PrintWriter writer;
    private final CharsetDecoder encoder;


    public WriterOutputStream(final HttpServerExchange exchange, final PrintWriter writer, final String charset) {
        this.exchange = exchange;
        this.writer = writer;
        this.charset = Charset.forName(charset);
        this.encoder = this.charset.newDecoder();
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b});
    }

    @Override
    public void write(byte[] b) throws IOException {
        CharBuffer res = encoder.decode(ByteBuffer.wrap(b));
        writer.write(res.toString());
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        CharBuffer res = encoder.decode(ByteBuffer.wrap(b, off, len));
        writer.write(res.toString());
    }
}
