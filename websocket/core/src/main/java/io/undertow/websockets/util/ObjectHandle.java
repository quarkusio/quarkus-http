package io.undertow.websockets.util;

public interface ObjectHandle<T> {

    T getInstance();

    void release();
}
