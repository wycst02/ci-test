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
package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.HttpUriDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * Server-side HTTP/2 stream context.
 * <p>
 * Handles incoming request HEADERS (parsing {@code :method}, {@code :scheme}, {@code :path}, {@code :authority}),
 * dispatches to the application handler, and manages response stream cleanup.
 *
 * @author wangyc
 */
public class Http2ServerStream extends Http2Stream {

    /** Non-null when the request should error out instead of normal dispatch. */
    private HttpStatus errorStatus;

    public Http2ServerStream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        super(reader, streamId, ctx);
    }

    @Override
    public String debugPrefix() {
        return "Server ";
    }

    public HttpStatus getErrorStatus() {
        return errorStatus;
    }

    @Override
    protected void onEndHeaders() {
        // Parse pseudo-headers
        method = HttpMethod.fromString(headers.get(":method").toString());
        if (method == null) {
            errorStatus = HttpStatus.METHOD_NOT_ALLOWED;
        }
        scheme = (String) headers.get(":scheme");
        path = headers.get(":path").toString();
        HttpUriDecoder uriDecoder = new HttpUriDecoder(false);
        uriDecoder.codec(path.getBytes());
        uriDecoder.endCodec();
        requestUri = uriDecoder.getUri();
        parameters = uriDecoder.getParameters();
        authority = (String) headers.get(":authority");

        // Parse content-type
        contentType = (String) headers.get("content-type");

        // Validate content-length
        String contentLengthString = (String) headers.get("content-length");
        if (contentLengthString != null) {
            declaredContentLength = Long.parseLong(contentLengthString);
            // Body too large, exceeds hard limit
            if (declaredContentLength > HttpConf.BODY_MAX_SIZE) {
                errorStatus = HttpStatus.REQUEST_ENTITY_TOO_LARGE;
            }
        }
    }

    @Override
    protected void submitRequest() {
        final Http2Request request = new Http2Request(this).errorStatus(errorStatus);
        requestInvoked = true;
        ctx.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    ctx.invokeHandle(request);
                } catch (IOException e) {
                    log.error("Invoke handle error, streamId=" + streamId, e);
                } finally {
                    if (!handovered) {
                        completeStream();
                    }
                }
            }
        });
    }

}
