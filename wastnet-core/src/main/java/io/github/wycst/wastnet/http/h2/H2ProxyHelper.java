package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpHeaderNames;
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Static helper for H2 proxy conversion (H2→H1, H1→H2, H2→H2, etc.).
 * <p>
 * Operates on an {@link Http2Stream} to build H1 requests from H2 stream state
 * and write H2 response frames back to the client from decoded H1 responses.
 *
 * @author wangyc
 */
public final class H2ProxyHelper {

    private static final byte[] COLON_SPACE_BYTES = ": ".getBytes();
    private static final byte[] CRLF_BYTES = "\r\n".getBytes();
    private static final byte[] H1_VERSION_CRLF = " HTTP/1.1\r\n".getBytes();

    // Pre-encoded HPACK literal for ":status: 502" (never-indexed, no huffman)
    // Hex: 10 07 3A 73 74 61 74 75 73 03 35 30 32
    private static final byte[] H2_ERROR_502_HEADER_PAYLOAD = {
        0x10, 0x07, 0x3A, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73, 0x03, 0x35, 0x30, 0x32
    };

    private H2ProxyHelper() {
    }

    /**
     * Build and send an HTTP/1.1 request to the target server, using the
     * internal headers map for efficient traversal (avoids HttpRequest wrappers).
     */
    public static void sendH1Request(Http2Stream streamCtx, ChannelContext targetCtx) throws IOException {
        String reqUri = streamCtx.path;
        if (reqUri == null || reqUri.isEmpty()) reqUri = "/";

        HttpBuf buf = HttpBuf.of(512);
        buf.write(streamCtx.method.name().getBytes());
        buf.write((byte) ' ');
        buf.write(reqUri.getBytes());
        buf.write(H1_VERSION_CRLF);

        // Iterate internal headers map directly, skip pseudo, content-length, host, transfer-encoding
        boolean writeHost = false;
        for (Map.Entry<String, Object> entry : streamCtx.headers.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (name.charAt(0) == ':'
                    || HttpHeaderNames.CONTENT_LENGTH.equals(name)
                    || HttpHeaderNames.TRANSFER_ENCODING.equals(name)) continue;
            if (HttpHeaderNames.HOST.equals(name)) {
                writeHost = true;
            }
            if (value instanceof List) {
                for (String v : (List<String>) value) {
                    writeH1HeaderLine(buf, name, v);
                }
            } else if (value != null) {
                writeH1HeaderLine(buf, name, String.valueOf(value));
            }
        }

        // :authority → H1 Host
        if (streamCtx.authority != null && !writeHost) {
            writeH1HeaderLine(buf, HttpHeaderNames.HOST, streamCtx.authority);
        }

        boolean isStream = streamCtx.needStreaming;
        if (!isStream) {
            long contentLengthVal = streamCtx.bodyData.length;
            if (contentLengthVal > 0) {
                writeH1HeaderLine(buf, HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLengthVal));
            }
            buf.write(CRLF_BYTES);
            targetCtx.write(buf.byteBuffer());
            if (contentLengthVal > 0) {
                targetCtx.write(streamCtx.bodyData);
            }
            targetCtx.flush();
        } else {
            writeH1HeaderLine(buf, HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            buf.write(CRLF_BYTES);
            targetCtx.write(buf.byteBuffer());
            InputStream bodyStream = streamCtx.getInputStream();
            byte[] chunkBuf = new byte[8192];
            int n;
            while ((n = bodyStream.read(chunkBuf)) != -1) {
                writeH1Chunk(targetCtx, chunkBuf, 0, n);
            }
            targetCtx.write("0\r\n\r\n".getBytes());
            targetCtx.flush();
        }
    }

    /**
     * Convert a decoded H1 response to H2 frames and write to client.
     * <p>
     * Body data is streamed in fixed-size DATA frames (up to 16KB each),
     * regardless of whether the original response was chunked/streaming/normal.
     * Ends with an empty DATA frame carrying END_STREAM to terminate the stream.
     *
     * @param streamCtx the H2 stream context
     * @param response  the decoded H1 response
     * @throws IOException if write to client fails
     */
    public static void writeResponse(Http2Stream streamCtx, HttpDecodedResponse response) throws IOException {
        try {
            // HEADERS frame (END_HEADERS set; END_STREAM only if no body)
            byte[] headerPayload = streamCtx.encodeHpackHeaders(response);
            boolean hasBody = response.getContentLength() > 0 || response.isStream();
            int hFlags = Http2Frame.END_HEADERS | (hasBody ? 0 : Http2Frame.END_STREAM);

            ByteBuffer headerFrame = streamCtx.createFrameBuffer(9 + headerPayload.length,
                    headerPayload.length, Http2Frame.FRAME_TYPE_HEADERS, hFlags);
            headerFrame.put(headerPayload);
            headerFrame.flip();
            streamCtx.writeFrame(headerFrame);

            if (!hasBody) return;

            // Unified body reading: stream response → bodyStream, otherwise wrap byte[]
            InputStream in = response.isStream() ? response.getBodyStream()
                    : new java.io.ByteArrayInputStream(response.getBody());

            int chunkSize = streamCtx.sendChunkSize();
            byte[] frameBuf = new byte[chunkSize];
            int n;
            while ((n = in.read(frameBuf)) != -1) {
                ByteBuffer dataFrame = streamCtx.createFrameBuffer(9 + n, n, Http2Frame.FRAME_TYPE_DATA, 0);
                dataFrame.put(frameBuf, 0, n);
                dataFrame.flip();
                streamCtx.writeDataFrame(dataFrame, n);
            }
        } finally {
            streamCtx.completeStream();
        }
    }

    /**
     * Send a gateway error (502) to the H2 client using pre-cached H2 frames.
     * Avoids HPACK re-encoding overhead of {@link #writeResponse(Http2Stream, HttpDecodedResponse)}.
     */
    public static void sendGatewayError(Http2Stream streamCtx) throws IOException {
        int hFlags = Http2Frame.END_HEADERS | Http2Frame.END_STREAM;
        ByteBuffer headerFrame = streamCtx.createFrameBuffer(9 + H2_ERROR_502_HEADER_PAYLOAD.length,
                H2_ERROR_502_HEADER_PAYLOAD.length, Http2Frame.FRAME_TYPE_HEADERS, hFlags);
        headerFrame.put(H2_ERROR_502_HEADER_PAYLOAD);
        headerFrame.flip();
        streamCtx.writeFrame(headerFrame);
        streamCtx.completeStream();
    }

    // ==================== H1 wire format helpers ====================

    private static void writeH1HeaderLine(HttpBuf buf, String name, String value) {
        buf.write(name.getBytes());
        buf.write(COLON_SPACE_BYTES);
        buf.write(value.getBytes());
        buf.write(CRLF_BYTES);
    }

    private static void writeH1Chunk(ChannelContext ctx, byte[] data, int offset, int len) throws IOException {
        ctx.write(Integer.toHexString(len).getBytes());
        ctx.write(CRLF_BYTES);
        ctx.write(data, offset, len);
        ctx.write(CRLF_BYTES);
    }
}
