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

package io.undertow.servlet.test.multipart;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.TreeSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import io.undertow.util.FileUtils;

/**
 * @author Stuart Douglas
 */
public class MultiPartServlet extends HttpServlet {

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        try {
            Collection<Part> parts = req.getParts();
            PrintWriter writer = resp.getWriter();
            writer.println("PARAMS:");
            for (Part part : parts) {
                writer.println("name: " + part.getName());
                writer.println("filename: " + part.getSubmittedFileName());
                writer.println("content-type: " + part.getContentType());
                Collection<String> headerNames = new TreeSet<>(part.getHeaderNames());
                for (String header : headerNames) {
                    writer.println(header + ": " + part.getHeader(header));
                }
                writer.println("size: " + part.getSize());
                writer.println("content: " + FileUtils.readFile(part.getInputStream()));
            }
        } catch (Exception e) {
            resp.getWriter().write("EXCEPTION: " + e.getClass());
        }
    }
}
