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

package io.undertow.websockets.servlet;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.ThreadSetupHandler;
import io.undertow.servlet.spec.ServletContextImpl;
import io.undertow.websockets.Extensions;
import io.undertow.websockets.ServerWebSocketContainer;
import io.undertow.websockets.UndertowContainerProvider;
import io.undertow.websockets.WebSocketDeploymentInfo;
import io.undertow.websockets.util.ContextSetupHandler;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectHandle;
import io.undertow.websockets.util.ObjectIntrospecter;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * @author Stuart Douglas
 */
public class Bootstrap implements ServletExtension {

    public static final String FILTER_NAME = "Undertow Web Socket Filter";

    @Override
    public void handleDeployment(DeploymentInfo deploymentInfo, ServletContext servletContext) {
        WebSocketDeploymentInfo info = (WebSocketDeploymentInfo) deploymentInfo.getServletContextAttributes().get(WebSocketDeploymentInfo.ATTRIBUTE_NAME);

        if (info == null) {
            return;
        }

        final List<ContextSetupHandler> setup = new ArrayList<>();
        setup.add(new ContextSetupHandler() {
            @Override
            public <T, C> Action<T, C> create(Action<T, C> action) {
                return new Action<T, C>() {
                    @Override
                    public T call(C context) throws Exception {
                        ClassLoader old = Thread.currentThread().getContextClassLoader();
                        Thread.currentThread().setContextClassLoader(deploymentInfo.getClassLoader());
                        try {
                            return action.call(context);
                        } finally {
                            Thread.currentThread().setContextClassLoader(old);
                        }
                    }
                };
            }
        });
        for (ThreadSetupHandler i : deploymentInfo.getThreadSetupActions()) {
            setup.add(new ContextSetupHandler() {
                @Override
                public <T, C> Action<T, C> create(Action<T, C> action) {
                    ThreadSetupHandler.Action<T, C> res = i.create(new ThreadSetupHandler.Action<T, C>() {
                        @Override
                        public T call(HttpServerExchange exchange, C context) throws Exception {
                            return action.call(context);
                        }
                    });
                    return new Action<T, C>() {
                        @Override
                        public T call(C context) throws Exception {
                            return res.call(null, context);
                        }
                    };
                }
            });
        }

        InetSocketAddress bind = null;
        if (info.getClientBindAddress() != null) {
            bind = new InetSocketAddress(info.getClientBindAddress(), 0);
        }

        ServerWebSocketContainer container = new ServletServerWebSocketContainer(new ObjectIntrospecter() {
            @Override
            public <T> ObjectFactory<T> createInstanceFactory(Class<T> clazz) {
                try {
                    io.undertow.servlet.api.InstanceFactory<T> factory = deploymentInfo.getClassIntrospecter().createInstanceFactory(clazz);
                    return new ObjectFactory<T>() {
                        @Override
                        public ObjectHandle<T> createInstance() {
                            try {
                                io.undertow.servlet.api.InstanceHandle<T> ret = factory.createInstance();
                                return new ObjectHandle<T>() {
                                    @Override
                                    public T getInstance() {
                                        return ret.getInstance();
                                    }

                                    @Override
                                    public void release() {
                                        ret.release();
                                    }
                                };
                            } catch (InstantiationException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    };
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }
        }, servletContext.getClassLoader(), new Supplier<EventLoopGroup>() {
            @Override
            public EventLoopGroup get() {
                if (info.getEventLoopGroup() != null) {
                    return info.getEventLoopGroup();
                }
                return UndertowContainerProvider.getDefaultEventLoopGroup();
            }
        }, setup, info.isDispatchToWorkerThread(), bind, info.getReconnectHandler(), new Supplier<Executor>() {
            @Override
            public Executor get() {
                if (info.getExecutor() != null) {
                    return info.getExecutor().get();
                }
                return GlobalEventExecutor.INSTANCE;
            }
        }, Extensions.EXTENSIONS, info.getMaxFrameSize());
        try {
            for (Class<?> annotation : info.getAnnotatedEndpoints()) {
                container.addEndpoint(annotation);
            }
            for (ServerEndpointConfig programatic : info.getProgramaticEndpoints()) {
                container.addEndpoint(programatic);
            }
        } catch (DeploymentException e) {
            throw new RuntimeException(e);
        }
        servletContext.setAttribute(ServerContainer.class.getName(), container);

        for (WebSocketDeploymentInfo.ContainerReadyListener listener : info.getListeners()) {
            listener.ready(container);
        }
        SecurityActions.addContainer(deploymentInfo.getClassLoader(), container);

        deploymentInfo.addListener(Servlets.listener(WebSocketListener.class));
        deploymentInfo.addDeploymentCompleteListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent sce) {
                container.validateDeployment();
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce) {

            }
        });
    }

    private static final class WebSocketListener implements ServletContextListener {

        private ServletServerWebSocketContainer container;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            container = (ServletServerWebSocketContainer) sce.getServletContext().getAttribute(ServerContainer.class.getName());
            FilterRegistration.Dynamic filter = sce.getServletContext().addFilter(FILTER_NAME, JsrWebSocketFilter.class);
            sce.getServletContext().addListener(JsrWebSocketFilter.LogoutListener.class);
            filter.setAsyncSupported(true);
            if (!container.getConfiguredServerEndpoints().isEmpty()) {
                filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            } else {
                container.setContextToAddFilter((ServletContextImpl) sce.getServletContext());
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            SecurityActions.removeContainer(sce.getServletContext().getClassLoader());
            container.close();
        }
    }

}
