/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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

package io.undertow.server;

import io.undertow.httpcore.StatusCodes;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Carter Kozak
 */
@RunWith(DefaultServer.class)
public class ExchangeCompletionListenerTestCase {

    private static final AtomicInteger count = new AtomicInteger();

    @BeforeClass
    public static void setup() {
        count.set(0);
        DefaultServer.setRootHandler(exchange -> {
            exchange.addExchangeCompleteListener(ex -> count.incrementAndGet());
            exchange.addExchangeCompleteListener(ex -> count.incrementAndGet());
        });
    }


    @Test
    public void testExchangeCompletionListener() throws IOException {
        final TestHttpClient client = new TestHttpClient();
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL());
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            EntityUtils.consume(result.getEntity());
            Assert.assertEquals(2, count.get());
        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
