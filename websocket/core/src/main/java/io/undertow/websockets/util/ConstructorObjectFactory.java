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

package io.undertow.websockets.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;


/**
 * @author Stuart Douglas
 */
public class ConstructorObjectFactory<T> implements ObjectFactory<T> {

    private final Constructor<T> constructor;

    public ConstructorObjectFactory(final Constructor<T> constructor) {
        constructor.setAccessible(true);
        this.constructor = constructor;
    }

    @Override
    public ObjectHandle<T> createInstance() {
        try {
            final T instance = constructor.newInstance();
            return new ImmediateObjectHandle<>(instance);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
