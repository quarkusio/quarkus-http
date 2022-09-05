package io.undertow.servlet.test.spec;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A servlet that echoes name and value pairs for received valid cookies only.
 *
 * @author Gael Marziou
 */
public class ValidCookieEchoServlet extends HttpServlet {

    @Override
    protected void doGet(final HttpServletRequest req,
                         final HttpServletResponse resp) throws ServletException, IOException {

        Cookie[] cookies = req.getCookies();

        PrintWriter out = resp.getWriter();
        for (Cookie cookie : cookies) {
            out.print("name='" + cookie.getName() + "'");
            out.print("value='" + cookie.getValue() + "'");
        }
    }
}
