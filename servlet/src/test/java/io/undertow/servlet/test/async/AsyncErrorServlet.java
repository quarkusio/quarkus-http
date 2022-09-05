package io.undertow.servlet.test.async;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.httpcore.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class AsyncErrorServlet extends HttpServlet {

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        req.startAsync();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                    resp.sendError(StatusCodes.INTERNAL_SERVER_ERROR);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        t.start();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req, resp);
    }
}
