package io.undertow.websockets.util;

public interface ObjectFactory<T> {

    ObjectHandle<T> createInstance();

}
