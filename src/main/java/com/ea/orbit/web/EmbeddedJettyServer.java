/*
 Copyright (C) 2016 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ea.orbit.web;

import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.Container;
import com.ea.orbit.exception.UncheckedException;
import com.ea.orbit.lifecycle.Startable;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.servlet.ServletProperties;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Servlet;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;

import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by joe@bioware.com on 2016-02-16.
 */

@Singleton
public class EmbeddedJettyServer implements Startable
{
    @Inject
    Container container;

    private static final Logger logger = Logger.getLogger(EmbeddedJettyServer.class.getName());

    private Server server;

    private int port = 9090;


    @Override
    public Task start()
    {

        final List<Class<?>> classes = container.getDiscoveredClasses();

        final ResourceConfig resourceConfig = new ResourceConfig();

        classes.stream()
                .filter(r -> r.isAnnotationPresent(Path.class) || r.isAnnotationPresent(Provider.class))
                .forEach(resourceConfig::register);


        final WebAppContext webAppContext = new WebAppContext();
        final ProtectionDomain protectionDomain = EmbeddedJettyServer.class.getProtectionDomain();
        final URL location = protectionDomain.getCodeSource().getLocation();
        logger.info(location.toExternalForm());
        webAppContext.setInitParameter("useFileMappedBuffer", "false");
        webAppContext.setWar(location.toExternalForm());
        // this sets the default service locator to one that bridges to the orbit container.
        webAppContext.getServletContext().setAttribute(ServletProperties.SERVICE_LOCATOR, container.getServiceLocator());
        webAppContext.setContextPath("/*");
        webAppContext.addServlet(new ServletHolder(new ServletContainer(resourceConfig)), "/*");

        final ContextHandler resourceContext = new ContextHandler();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setWelcomeFiles(new String[]{ "index.html" });
        resourceHandler.setBaseResource(Resource.newClassPathResource("/web"));

        resourceContext.setHandler(resourceHandler);
        resourceContext.setInitParameter("useFileMappedBuffer", "false");



        classes.stream()
                .filter(r -> r.isAnnotationPresent(Path.class) && Servlet.class.isAssignableFrom(r))
                .forEach(r ->
                {
                    javax.ws.rs.Path path = (Path) r.getAnnotation(Path.class);
                    webAppContext.addServlet((Class<? extends Servlet>) r, path.value());
                    resourceConfig.register(r);
                });

        List<Handler> handlers = new ArrayList<>(3);
        handlers.add(resourceContext);
        handlers.add(webAppContext);

        final ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(handlers.toArray(new Handler[handlers.size()]));

        server = new Server(port);
        server.setHandler(contexts);

        try
        {
            ///Initialize javax.websocket layer
            final ServerContainer serverContainer = WebSocketServerContainerInitializer.configureContext(webAppContext);

            classes.stream()
                    .filter(r -> r.isAnnotationPresent(ServerEndpoint.class))
                    .forEach(r ->
                    {
                        final ServerEndpoint annotation = (ServerEndpoint) r.getAnnotation(ServerEndpoint.class);

                        final ServerEndpointConfig serverEndpointConfig = ServerEndpointConfig.Builder.create(r, annotation.value()).configurator(new ServerEndpointConfig.Configurator()
                        {
                            @Override
                            public <T> T getEndpointInstance(final Class<T> endpointClass) throws InstantiationException
                            {
                                T instance = container.getServiceLocator().getService(endpointClass);

                                if(instance == null)
                                {
                                    try
                                    {
                                        instance = endpointClass.newInstance();
                                    }
                                    catch(Exception e)
                                    {
                                        throw new UncheckedException(e);
                                    }
                                }

                                return instance;
                            }
                        }).build();

                        try
                        {
                            serverContainer.addEndpoint(serverEndpointConfig);
                        }
                        catch(Exception e)
                        {
                            throw new UncheckedException(e);
                        }

                    });

        }
        catch (Exception e)
        {
            logger.severe("Error starting jetty: " + e.toString());
            throw new UncheckedException(e);
        }


        try
        {
            server.start();
        }
        catch (Exception e)
        {
            logger.severe("Error starting jetty: " + e.toString());
            throw new UncheckedException(e);
        }

        return Task.done();
    }

    @Override
    public Task stop()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            logger.severe("Error stopping jetty: " + e.toString());
            throw new UncheckedException(e);
        }
        return Task.done();
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(int port)
    {
        this.port = port;
    }
}
