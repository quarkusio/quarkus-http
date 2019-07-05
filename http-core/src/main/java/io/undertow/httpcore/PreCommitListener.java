package io.undertow.httpcore;

public interface PreCommitListener {

    void preCommit(HttpExchange exchange);
}
