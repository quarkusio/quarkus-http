package io.undertow.websockets.servlet;

import io.undertow.websockets.util.ConstructorObjectFactory;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectIntrospecter;

/**
 * @author Stuart Douglas
 */
public class TestClassIntrospector implements ObjectIntrospecter {

    public static final TestClassIntrospector INSTANCE = new TestClassIntrospector();

    @Override
    public <T> ObjectFactory<T> createInstanceFactory(final Class<T> clazz) {
        try {
            return new ConstructorObjectFactory<>(clazz.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
