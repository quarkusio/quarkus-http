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

import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
/**
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
final class SendHandlerAdapter implements GenericFutureListener<Future<? super Void>> {
    private final SendHandler handler;
    private static final SendResult OK = new SendResult();
    private volatile boolean done;

    SendHandlerAdapter(SendHandler handler) {
        this.handler = handler;
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        if (future.isSuccess()) {
            handler.onResult(OK);
        } else {
            handler.onResult(new SendResult(future.cause()));
        }
    }
}
