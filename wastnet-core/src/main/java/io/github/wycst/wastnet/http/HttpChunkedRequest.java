/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.List;
import java.util.Map;

public class HttpChunkedRequest extends HttpStreamRequest {

    public HttpChunkedRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] body, long contentLength, String contentType, ChannelContext ctx) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, body, contentLength, contentType, ctx, new HttpChunkedStream(body, ctx));
    }
}
