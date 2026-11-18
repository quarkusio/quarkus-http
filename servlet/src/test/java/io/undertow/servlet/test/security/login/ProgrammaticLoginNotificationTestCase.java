/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
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

package io.undertow.servlet.test.security.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.httpcore.StatusCodes;
import io.undertow.security.api.NotificationReceiver;
import io.undertow.security.api.SecurityNotification;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.SimpleServletTestCase;
import io.undertow.servlet.test.security.constraint.ServletIdentityManager;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;

/**
 * Regression test for <a href="https://github.com/quarkusio/quarkus-http/issues/169">#169</a>.
 * <p>
 * {@link HttpServletRequest#login(String, String)} and {@link HttpServletRequest#logout()} must notify the deployment's
 * {@link NotificationReceiver}s, flagged as programmatic, while the response can still be modified. Quarkus relies on
 * this to write (and clear) its form authentication credential cookie after a programmatic login.
 */
@RunWith(DefaultServer.class)
public class ProgrammaticLoginNotificationTestCase {

    static final String COOKIE_NAME = "quarkus-credential";

    static final List<SecurityNotification> NOTIFICATIONS = new CopyOnWriteArrayList<>();

    @BeforeClass
    public static void setup() throws ServletException {
        final PathHandler path = new PathHandler();
        final ServletContainer container = ServletContainer.Factory.newInstance();

        ServletIdentityManager identityManager = new ServletIdentityManager();
        identityManager.addUser("user1", "password1", "role1");

        DeploymentInfo builder = new DeploymentInfo()
                .setClassLoader(SimpleServletTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setDeploymentName("servletContext.war")
                .setIdentityManager(identityManager)
                .setLoginConfig(new LoginConfig("FORM", "Test Realm", "/login.html", "/error.html"))
                .addServlet(new ServletInfo("login", LoginLogoutServlet.class).addMapping("/login"))
                .addServlet(new ServletInfo("logout", LoginLogoutServlet.class).addMapping("/logout"))
                .addNotificationReceiver(new CredentialCookieReceiver());

        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();
        path.addPrefixPath(builder.getContextPath(), manager.start());

        DefaultServer.setRootHandler(path);
    }

    @Before
    public void reset() {
        NOTIFICATIONS.clear();
    }

    @Test
    public void testProgrammaticLoginNotifiesReceiversAndCanSetCookie() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/login");
            get.addHeader("username", "user1");
            get.addHeader("password", "password1");
            HttpResponse result = client.execute(get);
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            assertEquals("user1", HttpClientUtils.readResponse(result));

            SecurityNotification authenticated = findNotification(SecurityNotification.EventType.AUTHENTICATED);
            assertNotNull("programmatic login did not fire an AUTHENTICATED notification", authenticated);
            assertTrue("login() notification must be flagged as programmatic", authenticated.isProgramatic());
            assertEquals("user1", authenticated.getAccount().getPrincipal().getName());

            Header cookie = findSetCookie(result);
            assertNotNull("credential cookie written by the notification receiver is missing", cookie);
            assertTrue(cookie.getValue(), cookie.getValue().startsWith(COOKIE_NAME + "=user1"));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testFailedProgrammaticLoginDoesNotSetCookie() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/login");
            get.addHeader("username", "user1");
            get.addHeader("password", "wrong");
            HttpResponse result = client.execute(get);
            assertEquals(StatusCodes.UNAUTHORIZED, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);

            assertEquals(null, findNotification(SecurityNotification.EventType.AUTHENTICATED));
            assertEquals(null, findSetCookie(result));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testProgrammaticLogoutNotifiesReceiversAndCanClearCookie() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/logout");
            get.addHeader("username", "user1");
            get.addHeader("password", "password1");
            HttpResponse result = client.execute(get);
            assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);

            SecurityNotification loggedOut = findNotification(SecurityNotification.EventType.LOGGED_OUT);
            assertNotNull("programmatic logout did not fire a LOGGED_OUT notification", loggedOut);
            assertTrue("logout() notification must be flagged as programmatic", loggedOut.isProgramatic());

            Header cookie = findSetCookie(result);
            assertNotNull("credential cookie should be cleared on logout", cookie);
            assertTrue(cookie.getValue(), cookie.getValue().startsWith(COOKIE_NAME + "="));
            assertTrue(cookie.getValue(), cookie.getValue().toLowerCase().contains("max-age=0"));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    private static SecurityNotification findNotification(SecurityNotification.EventType type) {
        for (SecurityNotification notification : NOTIFICATIONS) {
            if (notification.getEventType() == type) {
                return notification;
            }
        }
        return null;
    }

    private static Header findSetCookie(HttpResponse result) {
        for (Header header : result.getHeaders("Set-Cookie")) {
            if (header.getValue().startsWith(COOKIE_NAME + "=")) {
                return header;
            }
        }
        return null;
    }

    /**
     * Mimics what Quarkus does with its form authentication mechanism: persist the identity in a cookie when the user
     * logs in programmatically, and clear it again on logout.
     */
    static final class CredentialCookieReceiver implements NotificationReceiver {
        @Override
        public void handleNotification(SecurityNotification notification) {
            NOTIFICATIONS.add(notification);
            if (!notification.isProgramatic()) {
                return;
            }
            switch (notification.getEventType()) {
                case AUTHENTICATED:
                    notification.getExchange().setResponseCookie(
                            new CookieImpl(COOKIE_NAME, notification.getAccount().getPrincipal().getName())
                                    .setPath("/").setHttpOnly(true));
                    break;
                case LOGGED_OUT:
                    notification.getExchange().setResponseCookie(
                            new CookieImpl(COOKIE_NAME, "").setPath("/").setMaxAge(0));
                    break;
                default:
                    break;
            }
        }
    }

    public static class LoginLogoutServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            try {
                req.login(req.getHeader("username"), req.getHeader("password"));
            } catch (ServletException e) {
                resp.setStatus(StatusCodes.UNAUTHORIZED);
                return;
            }
            if (req.getServletPath().equals("/logout")) {
                req.logout();
            }
            resp.getWriter().write(req.getRemoteUser() == null ? "" : req.getRemoteUser());
        }
    }
}
