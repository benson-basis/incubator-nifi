/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.security.cert.X509Certificate;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.http.HttpContextMap;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.annotation.OnScheduled;
import org.apache.nifi.processor.annotation.OnStopped;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import com.sun.jersey.api.client.ClientResponse.Status;

public class HandleHttpRequest extends AbstractProcessor {
    public static final String HTTP_CONTEXT_ID = "http.context.identifier";
    
    // Allowable values for client auth
    public static final AllowableValue CLIENT_NONE = new AllowableValue("No Authentication", "Processor will not authenticate clients. Anyone can communicate with this Processor anonymously");
    public static final AllowableValue CLIENT_WANT = new AllowableValue("Want Authentication", "Processor will try to verify the client but if unable to verify will allow the client to communicate anonymously");
    public static final AllowableValue CLIENT_NEED = new AllowableValue("Need Authentication", "Processor will reject communications from any client unless the client provides a certificate that is trusted by the TrustStore specified in the SSL Context Service");
    
    
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
        .name("Listening Port")
        .description("The Port to listen on for incoming HTTP requests")
        .required(true)
        .addValidator(StandardValidators.createLongValidator(0L, 65535L, true))
        .expressionLanguageSupported(false)
        .defaultValue("80")
        .build();
    public static final PropertyDescriptor HOSTNAME = new PropertyDescriptor.Builder()
        .name("Hostname")
        .description("The Hostname to bind to. If not specified, will bind to all hosts")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(false)
        .build();
    public static final PropertyDescriptor HTTP_CONTEXT_MAP = new PropertyDescriptor.Builder()
        .name("HTTP Context Map")
        .description("The HTTP Context Map Controller Service to use for caching the HTTP Request Information")
        .required(true)
        .identifiesControllerService(HttpContextMap.class)
        .build();
    public static final PropertyDescriptor SSL_CONTEXT = new PropertyDescriptor.Builder()
        .name("SSL Context Service")
        .description("The SSL Context Service to use in order to secure the server. If specified, the server will accept only HTTPS requests; otherwise, the server will accept only HTTP requests")
        .required(false)
        .identifiesControllerService(SSLContextService.class)
        .build();
    public static final PropertyDescriptor PATH_REGEX = new PropertyDescriptor.Builder()
        .name("Allowed Paths")
        .description("A Regular Expression that specifies the valid HTTP Paths that are allowed in the incoming URL Requests. If this value is specified and the path of the HTTP Requests does not match this Regular Expression, the Processor will respond with a 404: NotFound")
        .required(false)
        .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
        .expressionLanguageSupported(false)
        .build();
    public static final PropertyDescriptor ALLOW_GET = new PropertyDescriptor.Builder()
        .name("Allow GET")
        .description("Allow HTTP GET Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("true")
        .build();
    public static final PropertyDescriptor ALLOW_POST = new PropertyDescriptor.Builder()
        .name("Allow POST")
        .description("Allow HTTP POST Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("true")
        .build();
    public static final PropertyDescriptor ALLOW_PUT = new PropertyDescriptor.Builder()
        .name("Allow PUT")
        .description("Allow HTTP GET Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("true")
        .build();
    public static final PropertyDescriptor ALLOW_DELETE = new PropertyDescriptor.Builder()
        .name("Allow DELETE")
        .description("Allow HTTP GET Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("true")
        .build();
    public static final PropertyDescriptor ALLOW_HEAD = new PropertyDescriptor.Builder()
        .name("Allow HEAD")
        .description("Allow HTTP HEAD Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("false")
        .build();
    public static final PropertyDescriptor ALLOW_OPTIONS = new PropertyDescriptor.Builder()
        .name("Allow OPTIONS")
        .description("Allow HTTP GET Method")
        .required(true)
        .allowableValues("true", "false")
        .defaultValue("false")
        .build();
    public static final PropertyDescriptor ADDITIONAL_METHODS = new PropertyDescriptor.Builder()
        .name("Additional HTTP Methods")
        .description("A comma-separated list of non-standard HTTP Methods that should be allowed")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(false)
        .build();
    public static final PropertyDescriptor CLIENT_AUTH = new PropertyDescriptor.Builder()
        .name("Client Authentication")
        .description("Specifies whether or not the Processor should authenticate clients. This value is ignored if the <SSL Context Service> Property is not specified or the SSL Context provided uses only a KeyStore and not a TrustStore.")
        .required(true)
        .allowableValues(CLIENT_NONE, CLIENT_WANT, CLIENT_NEED)
        .build();
    
    
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("All content that is received is routed to the 'success' relationship")
        .build();
    
    private volatile Server server;
    private final BlockingQueue<HttpRequestContainer> containerQueue = new LinkedBlockingQueue<>(50);
    
    
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(PORT);
        descriptors.add(HOSTNAME);
        descriptors.add(SSL_CONTEXT);
        descriptors.add(HTTP_CONTEXT_MAP);
        descriptors.add(PATH_REGEX);
        descriptors.add(ALLOW_GET);
        descriptors.add(ALLOW_POST);
        descriptors.add(ALLOW_PUT);
        descriptors.add(ALLOW_DELETE);
        descriptors.add(ALLOW_HEAD);
        descriptors.add(ALLOW_OPTIONS);
        descriptors.add(ADDITIONAL_METHODS);

        return descriptors;
    }
    
    @Override
    public Set<Relationship> getRelationships() {
        return Collections.singleton(REL_SUCCESS);
    }
    
    
    @OnScheduled
    public void initializeServer(final ProcessContext context) throws Exception {
        final String host = context.getProperty(HOSTNAME).getValue();
        final int port = context.getProperty(PORT).asInteger();
        final SSLContextService sslService = context.getProperty(SSL_CONTEXT).asControllerService(SSLContextService.class);
        
        final String clientAuthValue = context.getProperty(CLIENT_AUTH).getValue();
        final boolean need;
        final boolean want;
        if ( CLIENT_NEED.equals(clientAuthValue) ) {
            need = true;
            want = false;
        } else if ( CLIENT_WANT.equals(clientAuthValue) ) {
            need = false;
            want = true;
        } else {
            need = false;
            want = false;
        }
        
        final SslContextFactory sslFactory = (sslService == null) ? null : createSslFactory(sslService, need, want);
        final Server server = new Server(port);
        
        // create the http configuration
        final HttpConfiguration httpConfiguration = new HttpConfiguration();
        if ( sslFactory == null ) {
            // create the connector
            final ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));

            // set host and port
            if (StringUtils.isNotBlank(host)) {
                http.setHost(host);
            }
            http.setPort(port);

            // add this connector
            server.setConnectors(new Connector[] {http});
        } else {
            // add some secure config
            final HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration);
            httpsConfiguration.setSecureScheme("https");
            httpsConfiguration.setSecurePort(port);
            httpsConfiguration.addCustomizer(new SecureRequestCustomizer());

            // build the connector
            final ServerConnector https = new ServerConnector(server,
                    new SslConnectionFactory(sslFactory, "http/1.1"),
                    new HttpConnectionFactory(httpsConfiguration));

            // set host and port
            if (StringUtils.isNotBlank(host)) {
                https.setHost(host);
            }
            https.setPort(port);

            // add this connector
            server.setConnectors(new Connector[] {https});
        }
        
        final Set<String> allowedMethods = new HashSet<>();
        if ( context.getProperty(ALLOW_GET).asBoolean() ) {
            allowedMethods.add("GET");
        }
        if ( context.getProperty(ALLOW_POST).asBoolean() ) {
            allowedMethods.add("POST");
        }
        if ( context.getProperty(ALLOW_PUT).asBoolean() ) {
            allowedMethods.add("PUT");
        }
        if ( context.getProperty(ALLOW_DELETE).asBoolean() ) {
            allowedMethods.add("DELETE");
        }
        if ( context.getProperty(ALLOW_HEAD).asBoolean() ) {
            allowedMethods.add("HEAD");
        }
        if ( context.getProperty(ALLOW_OPTIONS).asBoolean() ) {
            allowedMethods.add("OPTIONS");
        }
        
        final String additionalMethods = context.getProperty(ADDITIONAL_METHODS).getValue();
        if ( additionalMethods != null ) {
            for ( final String additionalMethod : additionalMethods.split(",") ) {
                final String trimmed = additionalMethod.trim();
                if ( !trimmed.isEmpty() ) {
                    allowedMethods.add(trimmed.toUpperCase());
                }
            }
        }
        
        final String pathRegex = context.getProperty(PATH_REGEX).getValue();
        final Pattern pathPattern = (pathRegex == null) ? null : Pattern.compile(pathRegex);
        
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(final String target, final Request baseRequest, final HttpServletRequest request, 
                        final HttpServletResponse response) throws IOException, ServletException {
                
                final String requestUri = request.getRequestURI();
                if ( !allowedMethods.contains(request.getMethod().toUpperCase()) ) {
                    getLogger().info("Sending back METHOD_NOT_ALLOWED response to {}; method was {}; request URI was {}", 
                            new Object[] {request.getRemoteAddr(), request.getMethod(), requestUri});
                    response.sendError(Status.METHOD_NOT_ALLOWED.getStatusCode());
                    return;
                }
                
                if ( pathPattern != null ) {
                    final URI uri;
                    try {
                        uri = new URI(requestUri);
                    } catch (final URISyntaxException e) {
                        throw new ServletException(e);
                    }
                    
                    if ( !pathPattern.matcher(uri.getPath()).matches() ) {
                        response.sendError(Status.NOT_FOUND.getStatusCode());
                        getLogger().info("Sending back NOT_FOUND response to {}; request was {} {}", 
                                new Object[] {request.getRemoteAddr(), request.getMethod(), requestUri});
                        return;
                    }
                }
                
                // TODO: If destination queues full, should send back a 503: Service Unavailable.
                // Right now, that information, though, is only in the ProcessSession, not the ProcessContext,
                // so it is not known to us. Should see if it can be added to the ProcessContext.
                final AsyncContext async = baseRequest.startAsync();
                final boolean added = containerQueue.offer(new HttpRequestContainer(request, response, async));
                
                if ( added ) {
                    getLogger().debug("Added Http Request to queue for {} {} from {}", new Object[] {request.getMethod(), requestUri, request.getRemoteAddr()});
                } else {
                    getLogger().info("Sending back a SERVICE_UNAVAILABLE response to {}; request was {} {}", 
                            new Object[] {request.getRemoteAddr(), request.getMethod(), request.getRemoteAddr()});

                    response.sendError(Status.SERVICE_UNAVAILABLE.getStatusCode());
                    response.flushBuffer();
                    async.complete();
                }
            }
        });
        
        this.server = server;
        server.start();
        
        getLogger().info("Server started and listening on port " + getPort());
    }
    
    protected int getPort() {
        for ( final Connector connector : server.getConnectors() ) {
            if ( connector instanceof ServerConnector ) {
                return ((ServerConnector) connector).getPort();
            }
        }
        
        throw new IllegalStateException("Server is not listening on any ports");
    }
    
    protected int getRequestQueueSize() {
        return containerQueue.size();
    }
    
    private SslContextFactory createSslFactory(final SSLContextService sslService, final boolean needClientAuth, final boolean wantClientAuth) {
        final SslContextFactory sslFactory = new SslContextFactory();
        
        sslFactory.setNeedClientAuth(needClientAuth);
        sslFactory.setWantClientAuth(wantClientAuth);
        
        if ( sslService.isKeyStoreConfigured() ) {
            sslFactory.setKeyStorePath(sslService.getKeyStoreFile());
            sslFactory.setKeyStorePassword(sslService.getKeyStorePassword());
            sslFactory.setKeyStoreType(sslService.getKeyStoreType());
        }

        if ( sslService.isTrustStoreConfigured() ) {
            sslFactory.setTrustStorePath(sslService.getTrustStoreFile());
            sslFactory.setTrustStorePassword(sslService.getTrustStorePassword());
            sslFactory.setTrustStoreType(sslService.getTrustStoreType());
        }
        
        return sslFactory;
    }
    
    @OnStopped
    public void shutdown() throws Exception {
        if ( server != null ) {
            getLogger().debug("Shutting down server");
            server.stop();
            server.join();
            getLogger().info("Shut down {}", new Object[] {server});
        }
    }
    
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final HttpRequestContainer container = containerQueue.poll();
        if ( container == null ) {
            return;
        }

        final long start = System.nanoTime();
        final HttpServletRequest request = container.getRequest();
        FlowFile flowFile = session.create();
        try {
            flowFile = session.importFrom(request.getInputStream(), flowFile);
        } catch (final IOException e) {
            getLogger().error("Failed to receive content from HTTP Request from {} due to {}", new Object[] {request.getRemoteAddr(), e});
            session.remove(flowFile);
            return;
        }
        
        final String contextIdentifier = UUID.randomUUID().toString();
        final Map<String, String> attributes = new HashMap<>();
        putAttribute(attributes, HTTP_CONTEXT_ID, contextIdentifier);
        putAttribute(attributes, "mime.type", request.getContentType());
        putAttribute(attributes, "http.servlet.path", request.getServletPath());
        putAttribute(attributes, "http.context.path", request.getContextPath());
        putAttribute(attributes, "http.method", request.getMethod());
        putAttribute(attributes, "http.query.string", request.getQueryString());
        putAttribute(attributes, "http.remote.host", request.getRemoteHost());
        putAttribute(attributes, "http.remote.addr", request.getRemoteAddr());
        putAttribute(attributes, "http.remote.user", request.getRemoteUser());
        putAttribute(attributes, "http.request.uri", request.getRequestURI());
        putAttribute(attributes, "http.auth.type", request.getAuthType());
        
        final Enumeration<String> headerNames = request.getHeaderNames();
        while ( headerNames.hasMoreElements() ) {
            final String headerName = headerNames.nextElement();
            final String headerValue = request.getHeader(headerName);
            putAttribute(attributes, "http.headers." + headerName, headerValue);
        }
        
        final Principal principal = request.getUserPrincipal();
        if ( principal != null ) {
            putAttribute(attributes, "http.principal.name", principal.getName());
        }
        
        final X509Certificate certs[] = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
        if ( certs != null && certs.length > 0 ) {
            final X509Certificate cert = certs[0];
            final String subjectDn = cert.getSubjectDN().getName();
            final String issuerDn = cert.getIssuerDN().getName();
            
            putAttribute(attributes, "http.subject.dn", subjectDn);
            putAttribute(attributes, "http.issuer.dn", issuerDn);
        }
        
        flowFile = session.putAllAttributes(flowFile, attributes);
        final long receiveMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        session.getProvenanceReporter().receive(flowFile, request.getRequestURI(), receiveMillis);
        session.transfer(flowFile, REL_SUCCESS);
        getLogger().info("Transferring {} to 'success'; received from {}", new Object[] {flowFile, request.getRemoteAddr()});
        
        final HttpContextMap contextMap = context.getProperty(HTTP_CONTEXT_MAP).asControllerService(HttpContextMap.class);
        contextMap.register(contextIdentifier, request, container.getResponse(), container.getContext());
    }
    
    private void putAttribute(final Map<String, String> map, final String key, final String value) {
        if ( value == null ) {
            return;
        }
        
        map.put(key, value);
    }
    
    
    private static class HttpRequestContainer {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext context;
        
        public HttpRequestContainer(final HttpServletRequest request, final HttpServletResponse response, final AsyncContext async) {
            this.request = request;
            this.response = response;
            this.context = async;
        }

        public HttpServletRequest getRequest() {
            return request;
        }

        public HttpServletResponse getResponse() {
            return response;
        }

        public AsyncContext getContext() {
            return context;
        }
    }
}
