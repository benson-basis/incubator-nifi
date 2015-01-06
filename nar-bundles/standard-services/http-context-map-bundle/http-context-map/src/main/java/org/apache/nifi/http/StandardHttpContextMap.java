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
package org.apache.nifi.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.nifi.controller.AbstractControllerService;

public class StandardHttpContextMap extends AbstractControllerService implements HttpContextMap {
    private final ConcurrentMap<String, Wrapper> wrapperMap = new ConcurrentHashMap<>();
    
    @Override
    public void register(final String identifier, final HttpServletRequest request, final HttpServletResponse response, final AsyncContext context) {
        final Wrapper wrapper = new Wrapper(request, response, context);
        final Wrapper existing = wrapperMap.putIfAbsent(identifier, wrapper);
        if ( existing != null ) {
            throw new IllegalStateException("HTTP Request already registered with identifier " + identifier);
        }
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
        @SuppressWarnings("unused")
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext async;
        
        public Wrapper(final HttpServletRequest request, final HttpServletResponse response, final AsyncContext async) {
            this.request = request;
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
