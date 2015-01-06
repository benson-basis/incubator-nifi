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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.http.HttpContextMap;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestHandleHttpResponse {

    @Test
    public void testResponse() throws InitializationException {
        final TestRunner runner = TestRunners.newTestRunner(HandleHttpResponse.class);
        runner.setProperty(HandleHttpResponse.STATUS_CODE, "${http.status.code}");
        runner.setProperty(HandleHttpResponse.HTTP_CONTEXT_MAP, "context-map");
        
        final MockContextMap service = new MockContextMap();
        runner.addControllerService("context-map", service);
        
        final Map<String, String> attributes = new HashMap<>();
        attributes.put(HandleHttpResponse.HTTP_CONTEXT_ID, "aa");
        attributes.put("http.status.code", "201");
        
        runner.enqueue("Hello World".getBytes(), attributes);
        
        runner.run();
        
        runner.assertAllFlowFilesTransferred(HandleHttpResponse.REL_SUCCESS, 1);
        final MockFlowFile mff = runner.getFlowFilesForRelationship(HandleHttpResponse.REL_SUCCESS).get(0);
        mff.assertContentEquals("Hello World");
        
        assertEquals(201, service.getStatusCode());
        assertTrue(Arrays.equals("Hello World".getBytes(), service.getContent()));
    }

    
    private static class MockContextMap extends AbstractControllerService implements HttpContextMap {
        private int statusCode;
        private Map<String, String> headers = new HashMap<>();
        private ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        @Override
        public void register(final String identifier, final HttpServletRequest request, final HttpServletResponse response, final AsyncContext context) {
        }
        
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public byte[] getContent() {
            return baos.toByteArray();
        }
        
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public HttpServletResponse getResponse(final String identifier) {
            try {
                if ( "aa".equals(identifier) ) {
                    final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
                    Mockito.doAnswer(new Answer<Object>() {
                        @Override
                        public Object answer(final InvocationOnMock invocation) throws Throwable {
                            statusCode = (Integer) invocation.getArguments()[0];
                            return null;
                        }
                    }).when(response).setStatus(Mockito.anyInt());
                    
                    Mockito.doAnswer(new Answer<ServletOutputStream>() {
                        @Override
                        public ServletOutputStream answer(InvocationOnMock invocation) throws Throwable {
                            return new ServletOutputStream() {
                                @Override
                                public void setWriteListener(WriteListener writeListener) {
                                }
                                
                                @Override
                                public void write(int b) throws IOException {
                                    baos.write(b);
                                }
                                
                                @Override
                                public void write(byte[] b) throws IOException {
                                    baos.write(b);
                                }
                                
                                @Override
                                public void write(byte[] b, int off, int len) throws IOException {
                                    baos.write(b, off, len);
                                }
                                
                                @Override
                                public void flush() throws IOException {
                                }
                                
                                @Override
                                public void close() throws IOException {
                                    baos.close();
                                }

                                @Override
                                public boolean isReady() {
                                    return true;
                                }
                            };
                        }
                    }).when(response).getOutputStream();
                    
                    Mockito.doAnswer(new Answer<Object>() {
                        @Override
                        public Object answer(InvocationOnMock invocation) throws Throwable {
                            headers.put(invocation.getArgumentAt(0, String.class), invocation.getArgumentAt(1, String.class));
                            return null;
                        }
                    }).when(response).setHeader(Mockito.anyString(), Mockito.anyString());
                    
                    return response;
                }
            } catch (final Exception e) {
                e.printStackTrace();
                Assert.fail(e.toString());
            }
            
            return null;
        }

        @Override
        public void complete(final String identifier) {
            if ( "aa".equals(identifier) ) {
                return;
            }
            
            throw new IllegalStateException("No HTTP Request registered with identifier " + identifier);
        }
    }
}
