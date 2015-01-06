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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.http.HttpContextMap;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.sun.jersey.api.client.ClientResponse.Status;

public class TestHandleHttpRequest {
    private TestRunner runner;
    private final HttpContextMap service = new MockContextMap();
    
    @BeforeClass
    public static void setupLog() {
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.nifi.processors", "DEBUG");
        System.setProperty("org.slf4j.simpleLogger.log.org.eclipse.jetty", "INFO");
    }
    
    @Before
    public void init() throws InitializationException {
        runner = TestRunners.newTestRunner(HandleHttpRequest.class);
        runner.addControllerService("controller-service", service);
        runner.setProperty(HandleHttpRequest.HTTP_CONTEXT_MAP, "controller-service");
        runner.setProperty(HandleHttpRequest.PORT, "9999");
    }
    
    @After
    public void shutdown() throws Exception {
        ((HandleHttpRequest) runner.getProcessor()).shutdown();
    }
    
    @Test(timeout=10000L)
    public void testGet() throws IOException, InterruptedException {
        // start up server but do not call the @OnStopped methods
        runner.run(1, false);

        try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
            final Thread t = callHttp(client, "GET", null);
            
            final MockFlowFile mff = waitForResponse();
            final String contextId = mff.getAttribute(HandleHttpRequest.HTTP_CONTEXT_ID);
            assertNotNull(contextId);
            
            sendStatus(contextId, 200, null);
            
            t.join();
        }
    }
    
    
    @Test(timeout=10000L)
    public void testPost() throws IOException, InterruptedException {
        // start up server but do not call the @OnStopped methods
        runner.run(1, false);

        try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
            final Thread t = callHttp(client, "POST", "Hello".getBytes());
            
            final MockFlowFile mff = waitForResponse();
            mff.assertContentEquals("Hello");
            final String contextId = mff.getAttribute(HandleHttpRequest.HTTP_CONTEXT_ID);
            assertNotNull(contextId);
            
            sendStatus(contextId, 200, "Hello".getBytes());
            
            t.join();
        }
    }
    
    
    @Test(timeout=10000L)
    public void testPut() throws IOException, InterruptedException {
        // start up server but do not call the @OnStopped methods
        runner.run(1, false);

        try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
            final Thread t = callHttp(client, "PUT", "Hello".getBytes());
            
            final MockFlowFile mff = waitForResponse();
            mff.assertContentEquals("Hello");
            final String contextId = mff.getAttribute(HandleHttpRequest.HTTP_CONTEXT_ID);
            assertNotNull(contextId);
            
            sendStatus(contextId, 200, "Hello".getBytes());
            
            t.join();
        }
    }
    
    @Test(timeout=10000L)
    public void testMethodNotAllowed() throws IOException, InterruptedException {
        // start up server but do not call the @OnStopped methods
        runner.run(1, false);

        try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
            final Thread t = callHttp(client, "OPTIONS", "/", "Hello".getBytes(), Status.METHOD_NOT_ALLOWED.getStatusCode());
            
            Thread.sleep(500L);
            final HandleHttpRequest proc = (HandleHttpRequest) runner.getProcessor();
            assertEquals(0, proc.getRequestQueueSize());

            runner.assertAllFlowFilesTransferred(HandleHttpRequest.REL_SUCCESS, 0);
            
            t.join();
        }
    }
    
    
    @Test(timeout=10000L)
    public void testNotFound() throws IOException, InterruptedException {
        runner.setProperty(HandleHttpRequest.PATH_REGEX, "/test/.*");
        
        // start up server but do not call the @OnStopped methods
        runner.run(1, false);

        try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
            Thread t = callHttp(client, "PUT", "/test/1", "Hello".getBytes());
            MockFlowFile mff = waitForResponse();
            mff.assertContentEquals("Hello");
            String contextId = mff.getAttribute(HandleHttpRequest.HTTP_CONTEXT_ID);
            assertNotNull(contextId);
            
            sendStatus(contextId, 200, "Hello".getBytes());
            t.join();
            
            runner.clearTransferState();
            
            t = callHttp(client, "GET", "/other/1", null, 404);
            Thread.sleep(500L);
            final HandleHttpRequest proc = (HandleHttpRequest) runner.getProcessor();
            assertEquals(0, proc.getRequestQueueSize());

            runner.assertAllFlowFilesTransferred(HandleHttpRequest.REL_SUCCESS, 0);
            
            t.join();
        }
    }
    
    
    private void sendStatus(final String contextId, final int code, final byte[] data) throws IOException {
        final HttpServletResponse response = service.getResponse(contextId);
        assertNotNull(response);
        response.setStatus(200);
        if ( data != null ) {
            response.getOutputStream().write(data);
        }
        response.flushBuffer();
        
        service.complete(contextId);
    }
    
    private MockFlowFile waitForResponse() throws InterruptedException {
        final HandleHttpRequest proc = (HandleHttpRequest) runner.getProcessor();
        
        // wait for a request to come in.
        while ( proc.getRequestQueueSize() == 0 ) {
            Thread.sleep(10L);
        }
        
        // handle the response.
        runner.run(1, false, false);
        
        runner.assertAllFlowFilesTransferred(HandleHttpRequest.REL_SUCCESS, 1);
        final MockFlowFile mff = runner.getFlowFilesForRelationship(HandleHttpRequest.REL_SUCCESS).get(0);
        return mff;
    }


    private Thread callHttp(final CloseableHttpClient client, final String method, final byte[] entity) {
        return callHttp(client, method, "/", entity);
    }
    
    private Thread callHttp(final CloseableHttpClient client, final String method, final String path, final byte[] entity) {
        return callHttp(client, method, path, entity, 200);
    }
    
    private Thread callHttp(final CloseableHttpClient client, final String method, final String path, final byte[] entity, final int expectedStatusCode) {
        final String uri = "http://localhost:9999" + path;
        final HttpUriRequest request;
        
        switch (method.toUpperCase()) {
            case "GET":
                request = new HttpGet(uri);
                break;
            case "POST":
                final HttpPost post = new HttpPost(uri);
                if ( entity != null ) {
                    post.setEntity(new ByteArrayEntity(entity));
                }
                request = post;
                break;
            case "PUT":
                final HttpPut put = new HttpPut(uri);
                if ( entity != null ) {
                    put.setEntity(new ByteArrayEntity(entity));
                }
                request = put;
                break;
            case "DELETE":
                request = new HttpDelete(uri);
                break;
            case "OPTIONS":
                request = new HttpOptions(uri);
                break;
            default:
                throw new IllegalArgumentException();
        }
        
        request.setHeader("header", "value");

        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final CloseableHttpResponse response = client.execute(request);
                    final int statusCode = response.getStatusLine().getStatusCode();
                    Assert.assertEquals(expectedStatusCode, statusCode);
                    
                    response.close();
                } catch (final Exception e) {
                    e.printStackTrace();
                    Assert.fail("Exception from HTTP Request");
                }
            }
        });
        
        t.start();
        
        return t;
    }
    
    
    private static class MockContextMap extends AbstractControllerService implements HttpContextMap {
        private final Map<String, Wrapper> wrapperMap = new HashMap<>();
        
        @Override
        public void register(final String identifier, final HttpServletRequest request, final HttpServletResponse response, final AsyncContext context) {
            wrapperMap.put(identifier, new Wrapper(response, context));
        }

        @Override
        public HttpServletResponse getResponse(final String identifier) {
            final Wrapper wrapper = wrapperMap.get(identifier);
            if ( wrapper == null ) {
                return null;
            }
            
            return wrapper.getResponse();
        }

        @Override
        public void complete(final String identifier) {
            final Wrapper wrapper = wrapperMap.remove(identifier);
            if ( wrapper == null ) {
                throw new IllegalStateException("No HTTP Request registered with identifier " + identifier);
            }
            
            wrapper.getAsync().complete();
        }

        private static class Wrapper {
            private final HttpServletResponse response;
            private final AsyncContext async;
            
            public Wrapper(final HttpServletResponse response, final AsyncContext async) {
                this.response = response;
                this.async = async;
            }

            public HttpServletResponse getResponse() {
                return response;
            }

            public AsyncContext getAsync() {
                return async;
            }
            
        }
    }
}
