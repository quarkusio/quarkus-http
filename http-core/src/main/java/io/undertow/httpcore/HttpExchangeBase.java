package io.undertow.httpcore;


public abstract class HttpExchangeBase implements HttpExchange {


    private boolean requestTerminated;
    private boolean responseTerminated;
    private CompletedListener completedListener;

    @Override
    public void setCompletedListener(CompletedListener listener) {
        this.completedListener = listener;
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
}
