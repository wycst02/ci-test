package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.List;
import java.util.Map;

public class HttpChunkedRequest extends HttpStreamRequest {

    public HttpChunkedRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] body, long contentLength, String contentType, ChannelContext ctx) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, body, contentLength, contentType, ctx, new HttpChunkedStream(body, ctx));
    }
}
