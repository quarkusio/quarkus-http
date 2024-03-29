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

package io.undertow.servlet.attribute;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import io.undertow.attribute.ExchangeAttribute;
import io.undertow.attribute.ExchangeAttributeBuilder;
import io.undertow.attribute.ReadOnlyAttributeException;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * The request session ID
 *
 * @author Stuart Douglas
 */
public class ServletSessionIdAttribute implements ExchangeAttribute {

    public static final String SESSION_ID_SHORT = "%S";
    public static final String SESSION_ID = "%{SESSION_ID}";

    public static final ServletSessionIdAttribute INSTANCE = new ServletSessionIdAttribute();

    @Override
    public String readAttribute(final HttpServerExchange exchange) {
        ServletRequestContext context = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        if (context != null) {
            ServletRequest req = context.getServletRequest();
            if (req instanceof HttpServletRequest) {
                HttpSession session = ((HttpServletRequest) req).getSession(false);
                if (session != null) {
                    return session.getId();
                }
            }
        }
        return null;
    }

    @Override
    public void writeAttribute(final HttpServerExchange exchange, final String newValue) throws ReadOnlyAttributeException {
        throw new ReadOnlyAttributeException("Session ID", newValue);
    }

    public static final class Builder implements ExchangeAttributeBuilder {

        @Override
        public String name() {
            return "Session ID attribute";
        }

        @Override
        public ExchangeAttribute build(final String token) {
            if (token.equals(SESSION_ID) || token.equals(SESSION_ID_SHORT)) {
                return INSTANCE;
            }
            return null;
        }

        @Override
        public int priority() {
            return 0;
        }
    }
}
