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

package io.undertow.websockets;

import io.undertow.websockets.annotated.AnnotatedEndpointFactory;
import io.undertow.websockets.util.ObjectFactory;

import jakarta.websocket.ClientEndpointConfig;

/**
 * @author Stuart Douglas
 */
public class ConfiguredClientEndpoint extends SessionContainer {

    private final ClientEndpointConfig config;
    private final AnnotatedEndpointFactory factory;
    private final EncodingFactory encodingFactory;
    private final ObjectFactory<?> objectFactory;

    public ConfiguredClientEndpoint(final ClientEndpointConfig config, final AnnotatedEndpointFactory factory, final EncodingFactory encodingFactory, ObjectFactory<?> objectFactory) {
        this.config = config;
        this.factory = factory;
        this.encodingFactory = encodingFactory;
        this.objectFactory = objectFactory;
    }

    public ConfiguredClientEndpoint() {
        this(null, null, null, null);
    }

    public ClientEndpointConfig getConfig() {
        return config;
    }

    public AnnotatedEndpointFactory getFactory() {
        return factory;
    }

    public EncodingFactory getEncodingFactory() {
        return encodingFactory;
    }

    public ObjectFactory<?> getInstanceFactory() {
        return objectFactory;
    }
}
