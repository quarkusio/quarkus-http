package io.undertow.httpcore;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;

import org.jboss.logging.Logger;

import io.netty.buffer.ByteBuf;

public abstract class HttpExchangeBase implements HttpExchange, OutputChannel {

    private static final Logger log = Logger.getLogger(HttpExchangeBase.class);

    private static final BiConsumer<InputChannel, HttpExchangeBase> DRAIN_CALLBACK = new BiConsumer<InputChannel, HttpExchangeBase>() {
        @Override
        public void accept(InputChannel channel, HttpExchangeBase exchange) {
            while (channel.isReadable()) {
                try {
                    if(channel.readAsync() == null) {
                        return;
                    }
                } catch (IOException e) {
                    log.debugf(e, "Error draining request");
                    exchange.close();
                    return;
                }
            }
            channel.setReadHandler(this, exchange);
        }
    };

    private boolean requestTerminated;
    private boolean responseTerminated;
    private CompletedListener completedListener;
    private BlockingHttpExchange blockingHttpExchange;

    private int writeFunctionCount;
    private WriteFunction[] writeFunctions;
    protected PreCommitListener preCommitListener;
    private boolean responseStarted;

    @Override
    public void setCompletedListener(CompletedListener listener) {
        this.completedListener = listener;
    }

    @Override
    public void setPreCommitListener(PreCommitListener listener) {
        this.preCommitListener = listener;
    }

    /**
     * Force the codec to treat the request as fully read.  Should only be invoked by handlers which downgrade
     * the socket or implement a transfer coding.
     */
    protected void terminateRequest() {
        if (requestTerminated) {
            // idempotent
            return;
        }
        requestTerminated = true;
        if (responseTerminated) {
            if (completedListener != null) {
                completedListener.completed(this);
            }
        }
    }

    /**
     * Force the codec to treat the response as fully written.  Should only be invoked by handlers which downgrade
     * the socket or implement a transfer coding.
     */
    protected void terminateResponse() {
        if (responseTerminated) {
            // idempotent
            return;
        }
        responseTerminated = true;
        if (requestTerminated) {
            if (completedListener != null) {
                completedListener.completed(this);
            }
        }
    }

    @Override
    public void setBlockingHttpExchange(BlockingHttpExchange exchange) {
        if (blockingHttpExchange != null) {
            return;
        }
        this.blockingHttpExchange = exchange;
    }

    @Override
    public OutputStream getOutputStream() {
        if (blockingHttpExchange == null) {
            blockingHttpExchange = new DefaultBlockingHttpExchange(this);
        }
        return blockingHttpExchange.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        if (blockingHttpExchange == null) {
            blockingHttpExchange = new DefaultBlockingHttpExchange(this);
        }
        return blockingHttpExchange.getInputStream();
    }




    /**
     * Returns true if the completion handler for this exchange has been invoked, and the request is considered
     * finished.
     */
    @Override
    public boolean isComplete() {
        return requestTerminated && responseTerminated;
    }


    /**
     * Returns true if all data has been read from the request, or if there
     * was not data.
     *
     * @return true if the request is complete
     */
    @Override
    public boolean isRequestComplete() {
        return requestTerminated;
    }

    /**
     * @return true if the responses is complete
     */
    @Override
    public boolean isResponseComplete() {
        return responseTerminated;
    }

    public void endExchange() {

        if (blockingHttpExchange != null) {
            //always close the blocking exchange first
//            if (isInIoThread()) {
//                dispatch(new Runnable() {
//                    @Override
//                    public void run() {
//                        endExchange();
//                    }
//                });
//                return this;
//            }
            try {
                blockingHttpExchange.close();
            } catch (IOException e) {
                close();
            }
        }
        if (!isRequestComplete()) {
            DRAIN_CALLBACK.accept(getInputChannel(), this);
        }

        if (!isResponseComplete()) {
            getOutputChannel().writeAsync(null, true, null, null);
        }
    }

    @Override
    public final void writeAsync(String data) {
        OutputChannel.super.writeAsync(data);
    }

    @Override
    public final void writeAsync(String data, Charset charset) {
        OutputChannel.super.writeAsync(data, charset);

    }

    @Override
    public final <T> void writeAsync(String data, Charset charset, boolean last, IoCallback<T> callback, T context) {
        OutputChannel.super.writeAsync(data, charset, last, callback, context);

    }

    @Override
    public void addWriteFunction(final WriteFunction listener) {
        final int writeFunctionCount = this.writeFunctionCount++;
        WriteFunction[] writeFunctions = this.writeFunctions;
        if (writeFunctions == null || writeFunctions.length == writeFunctionCount) {
            WriteFunction[] old = writeFunctions;
            this.writeFunctions = writeFunctions = new WriteFunction[writeFunctionCount + 2];
            if (old != null) {
                System.arraycopy(old, 0, writeFunctions, 0, writeFunctionCount);
            }
        }
        writeFunctions[writeFunctionCount] = listener;
    }

    @Override
    public OutputChannel getOutputChannel() {
        return this;
    }
    protected boolean isResponseStarted() {
        return responseStarted;
    }
    @Override
    public final <T> void writeAsync(ByteBuf data, boolean last, IoCallback<T> callback, T context) {
        data = processData(data, last);
        writeAsync0(data, last, callback, context);
    }

    protected abstract  <T> void writeAsync0(ByteBuf data, boolean last, IoCallback<T> callback, T context);


    @Override
    public final void writeBlocking(ByteBuf data, boolean last) throws IOException {

        data = processData(data, last);
        writeBlocking0(data, last);

    }

    protected abstract void writeBlocking0(ByteBuf data, boolean last) throws IOException;


    private ByteBuf processData(ByteBuf data, boolean last) {
        if (!responseStarted) {
            if(preCommitListener != null) {
                preCommitListener.preCommit(this);
            }
            if (writeFunctions != null) {
                for (int i = 0; i < writeFunctionCount; ++i) {
                    data = writeFunctions[i].preWrite(data, last);
                }
            }
            if (last) {
                if (data == null) {
                    if (!containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        addResponseHeader(HttpHeaderNames.CONTENT_LENGTH, "0");
                    }
                } else {
                    if (!containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                        addResponseHeader(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(data.readableBytes()));
                    }
                }
            } else {
                if (!containsResponseHeader(HttpHeaderNames.CONTENT_LENGTH)) {
                    setResponseHeader(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
                }
            }
            responseStarted = true;
        } else {
            if (writeFunctions != null) {
                for (int i = 0; i < writeFunctionCount; ++i) {
                    data = writeFunctions[i].preWrite(data, last);
                }
            }
        }
        return data;
    }
}
