/*
 * Copyright 2010-2014 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.commons.skeleton.modules;

import java.io.IOException;
import java.net.ServerSocket;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.testng.Assert;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;

public abstract class AbstractBaseServerModuleTest {

    protected Server startServer(final Module... modules) throws Exception {
        final Injector injector = Guice.createInjector(modules);

        final Server server = new Server(getPort());
        final ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addEventListener(new GuiceServletContextListener() {
            @Override
            protected Injector getInjector() {
                return injector;
            }
        });

        servletContextHandler.addFilter(GuiceFilter.class, "/*", null);
        servletContextHandler.addServlet(DefaultServlet.class, "/*");
        server.setHandler(servletContextHandler);
        server.start();

        final Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    server.join();
                } catch (final InterruptedException ignored) {
                }
            }
        };
        t.setDaemon(true);
        t.start();
        Assert.assertTrue(server.isRunning());
        return server;
    }

    private int getPort() {
        final int port;
        try {
            final ServerSocket socket = new ServerSocket(0);
            port = socket.getLocalPort();
            socket.close();
        } catch (final IOException e) {
            Assert.fail();
            return -1;
        }

        return port;
    }
}
