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
package io.undertow.servlet.test.listener.ordering;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

import io.undertow.servlet.test.util.Tracker;

public class SecondListener implements ServletRequestListener {

    @Override
    public void requestInitialized(ServletRequestEvent sre) {
        Tracker.addAction(SecondListener.class.getSimpleName());
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre) {
        Tracker.addAction(SecondListener.class.getSimpleName());
    }
}
