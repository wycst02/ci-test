package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Client-side HTTP/2 stream context.
 * <p>
 * Handles incoming response HEADERS (parsing {@code :status}),
 * provides response body data, and manages stream cleanup.
 *
 * @author wangyc
 */
public class Http2ClientStream extends Http2Stream {

    /** Response status code parsed from {@code :status} pseudo-header */
    int statusCode;

    public Http2ClientStream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        super(reader, streamId, ctx);
    }

    @Override
    public String debugPrefix() {
        return "Client ";
    }

    @Override
    protected void onEndHeaders() {
        // Parse :status pseudo-header
        Object statusObj = headers.get(":status");
        if (statusObj != null) {
            statusCode = Integer.parseInt(statusObj.toString());
        }

        // Parse content-type
        contentType = (String) headers.get("content-type");

        // Parse content-length
        String contentLengthString = (String) headers.get("content-length");
        if (contentLengthString != null) {
            declaredContentLength = Long.parseLong(contentLengthString);
        }
    }

    @Override
    protected void submitRequest() {
        requestInvoked = true;
        // TODO: notify response callback when client response handling is implemented
    }
}
