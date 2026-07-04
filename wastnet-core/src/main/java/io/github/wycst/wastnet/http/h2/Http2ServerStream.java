package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
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
            public void run() {
                try {
                    ctx.invokeHandle(request);
                } catch (IOException e) {
                    log.error("Invoke handle error, streamId=" + streamId, e);
                } finally {
                    if (!handover) {
                        completeStream();
                    }
                }
            }
        });
    }

}
