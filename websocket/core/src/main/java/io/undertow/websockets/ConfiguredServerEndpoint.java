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

import java.util.List;

import jakarta.websocket.Extension;
import jakarta.websocket.server.ServerEndpointConfig;

import io.undertow.websockets.annotated.AnnotatedEndpointFactory;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.PathTemplate;

/**
 * @author Stuart Douglas
 */
public class ConfiguredServerEndpoint extends SessionContainer {

    private final ServerEndpointConfig endpointConfiguration;
    private final AnnotatedEndpointFactory annotatedEndpointFactory;
    private final ObjectFactory<?> endpointFactory;
    private final PathTemplate pathTemplate;
    private final EncodingFactory encodingFactory;
    private final List<Extension> extensions;

    public ConfiguredServerEndpoint(final ServerEndpointConfig endpointConfiguration, final ObjectFactory<?> endpointFactory, final PathTemplate pathTemplate, final EncodingFactory encodingFactory, AnnotatedEndpointFactory annotatedEndpointFactory, List<Extension> installed) {
        this.endpointConfiguration = endpointConfiguration;
        this.endpointFactory = endpointFactory;
        this.pathTemplate = pathTemplate;
        this.encodingFactory = encodingFactory;
        this.annotatedEndpointFactory = annotatedEndpointFactory;
        this.extensions = installed;
    }

    public ConfiguredServerEndpoint(final ServerEndpointConfig endpointConfiguration, final ObjectFactory<?> endpointFactory, final PathTemplate pathTemplate, final EncodingFactory encodingFactory) {
        this.endpointConfiguration = endpointConfiguration;
        this.endpointFactory = endpointFactory;
        this.pathTemplate = pathTemplate;
        this.encodingFactory = encodingFactory;
        this.annotatedEndpointFactory = null;
        this.extensions = endpointConfiguration.getExtensions();
    }
    public ServerEndpointConfig getEndpointConfiguration() {
        return endpointConfiguration;
    }

    public ObjectFactory<?> getEndpointFactory() {
        return endpointFactory;
    }

    public PathTemplate getPathTemplate() {
        return pathTemplate;
    }

    public EncodingFactory getEncodingFactory() {
        return encodingFactory;
    }


    public AnnotatedEndpointFactory getAnnotatedEndpointFactory() {
        return annotatedEndpointFactory;
    }

    /**
     * Return the websocket extensions configured.
     *
     * @return the list of extensions, the empty list if none.
     */
    public List<Extension> getExtensions() {
        return extensions;
    }

}
