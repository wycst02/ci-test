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

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * HTTP/2 stream context.
 * <p>
 * Represents a bidirectional flow of data between client and server within an HTTP/2 connection.
 *
 * @author wangyc
 */
public abstract class Http2Stream {

    final Log log = LogFactory.getLog(getClass());
    // Reusable temp buffer for HPACK length prefix encoding (stream is single-threaded)
    private static final byte[] _HPACK_TMP = new byte[9];

    // ==================== Abstract template methods ====================

    /**
     * Template method: parse and validate headers after HPACK decode.
     * <p>
     * Server: parses request pseudo-headers ({@code :method}, {@code :scheme}, {@code :path}, {@code :authority})
     * Client: parses response pseudo-headers ({@code :status})
     */
    protected final void endHeaders() {
        endHeaders = true;
        onEndHeaders();
    }

    /**
     * Hook invoked by {@link #endHeaders()} after setting the flag.
     * Subclasses implement their own header parsing logic.
     */
    protected abstract void onEndHeaders();

    /**
     * Called when headers are fully received and body is ready (or stream ended).
     * <p>
     * Server: creates {@link Http2Request} and dispatches to handler.
     * Client: notifies response callback.
     */
    protected abstract void submitRequest();

    /**
     * Returns log prefix for DEBUG output, e.g. {@code "Server "} or {@code "Client "}.
     */
    public abstract String debugPrefix();
    final Http2MessageReader reader;
    final int streamId;
    final ChannelContext ctx;
    final Map<String, Object> headers;

    /**
     * Maximum bodyData capacity before switching to streaming mode.
     * Equals max(INITIAL_RECEIVE_WINDOW_SIZE * 2, MAX_BODY_IN_MEMORY).
     */
    static final int MAX_STREAM_CAPACITY_SIZE = Math.max(Http2MessageReader.INITIAL_RECEIVE_WINDOW_SIZE << 1, HttpConf.MAX_BODY_IN_MEMORY);

    // depends on the client's initial window size
    long sendWindow;
    // depends on the server's initial window size
    long receiveWindow = Http2MessageReader.INITIAL_RECEIVE_WINDOW_SIZE;
    boolean waitingContinuation;
    boolean endHeaders;
    boolean endStream;
    boolean requestInvoked;
    boolean handovered;
    boolean endStreamSent;

    /**
     * Declared content-length from :content-length pseudo-header, -1 if not declared
     */
    long declaredContentLength = -1;

    /**
     * Whether body exceeds MAX_STREAM_CAPACITY_SIZE, needs streaming
     */
    boolean needStreaming;

    /**
     * Buffer to store DATA frame payloads before streaming
     */
    byte[] bodyData;

    /**
     * Streaming input stream, created when handleRequest is called
     */
    Http2BodyInputStream bodyStream;

    // Parsed from headers at endHeaders()
    HttpMethod method;
    String scheme;
    String path;           // header: :path -> URI
    String requestUri;     // decoded URI without query string
    Map<String, List<String>> parameters;
    String contentType;
    String authority;

    Map<String, Object> attrs; // user attributes (lazy init)

    // data stream
    public Http2Stream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
        this.reader = reader;
        this.streamId = streamId;
        this.ctx = ctx;
        this.sendWindow = reader.initialSendWindowSize;
        this.headers = streamId == 0 ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>();
        this.bodyData = HttpRequest.EMPTY_BODY;
    }

    Map<String, Object> getAttrs() {
        if (attrs == null) {
            attrs = new HashMap<String, Object>();
        }
        return attrs;
    }

    /**
     * Single entry point for data stream frame dispatching.
     * <p>
     * All frames arriving on a non-zero stream are routed here and delegated
     * to the appropriate handler based on type.
     */
    void handleFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        Http2FrameType type = frame.getType();
        // HEADERS: carry request headers (and optionally END_STREAM)
        if (type == Http2FrameType.HEADERS) {
            onHeadersFrame(frame, ctx);
            return;
        }
        // DATA: carry request body payload
        if (type == Http2FrameType.DATA) {
            onDataFrame(frame, ctx);
            return;
        }

        // CONTINUATION: carry CONTINUATION frames
        if (type == Http2FrameType.CONTINUATION && waitingContinuation) {
            // the scene rarely appears,  reuse header frame processing
            onHeadersFrame(frame, ctx);
            return;
        }

        // ---- control frames on a data stream ----
        switch (type) {
            // RST_STREAM: peer aborted the stream
            case RST_STREAM:
                // Flush accumulated consumed bytes to prevent connection-level window leak
                flushConsumedWindowUpdate();
                reader.removeStream(streamId);
                return;
            // SETTINGS / PING / GOAWAY / PUSH_PROMISE must only appear on
            // stream 0 per RFC 7540; receiving them here is a protocol error
            case SETTINGS:
            case PING:
            case GOAWAY:
            case PUSH_PROMISE:
                reader.closeConnection(ctx);
                return;
            // WINDOW_UPDATE: peer grants additional send capacity
            case WINDOW_UPDATE: {
                int increment = Http2MessageReader.readInt32(frame.getFrameData(), 9);
                reader.connectSendWindow += increment;
                if ((sendWindow += increment) > Integer.MAX_VALUE || increment < 0) {
                    reader.sendRstStreamFrame(ctx, streamId, 3);
                    reader.removeStream(streamId);
                }
                reader.wakeupSendWU();
            }
            // PRIORITY are silently ignored
        }
    }

    /**
     * Handle an incoming HEADERS frame.
     * <p>
     * Manages HPACK header decoding, debug logging, end-of-headers parsing,
     * and early request submission (endStream or needStreaming).
     * If END_HEADERS is not set, returns early and waits for continuation.
     */
    void onHeadersFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        if (endHeaders) { // PROTOCOL_ERROR: headers already completed on this stream
            reader.sendRstStreamFrame(ctx, streamId, 1);  // PROTOCOL_ERROR
            reader.removeStream(streamId);
            return;
        }
        byte[] frameData = frame.getFrameData();
        int offset = frame.payloadActualOffset;
        int len = frame.payloadActualLength;

        try {
            reader.http2HpackCodec.decodeTo(frameData, offset, len, headers);
        } catch (Throwable throwable) {
            log.error("Hpack Error, Stream Id: " + streamId + "-> Hpack Header Bytes: " + Utils.printHexString(Arrays.copyOfRange(frameData, offset, offset + len), ' '));
            reader.closeConnection(ctx);
            return;
        }

        // END_HEADERS not set, wait for continuation HEADERS frame
        if (!frame.isEndHeaders()) {
            waitingContinuation = true;
            return;
        }

        try { // END_HEADERS completed, parse and validate headers
            endHeaders();
        } catch (Exception e) { // Body too large, send RST_STREAM
            reader.sendRstStreamFrame(ctx, streamId, e instanceof NullPointerException ? 1 : 7);
            reader.removeStream(streamId);
            return;
        }

        if (frame.isEndStream()) { // END_STREAM set in HEADERS frame, no body
            this.endStream = true;
            this.bodyStream = Http2BodyInputStream.EMPTY;
            submitRequest();
        }
    }

    /**
     * Handle an incoming DATA frame.
     * <p>
     * Manages all DATA-related logic including protocol checks, window updates
     * (connection-level and stream-level, both consumption-driven after invocation,
     * receive-driven before invocation),
     * body accumulation / streaming, and discard mode for early-response cases.
     */
    void onDataFrame(Http2Frame frame, ChannelContext ctx) throws IOException {
        int len = frame.payloadActualLength;

        // DATA before END_HEADERS is a protocol error
        if (!endHeaders) {
            reader.sendRstStreamFrame(ctx, streamId, 1);
            reader.removeStream(streamId);
            return;
        }

        // Frame has been received; decrement windows accordingly
        receiveWindow -= len;
        reader.connectRecvWindow -= len;

        // Post-invocation: consumption-driven stream + connection WU
        if (requestInvoked) {
            feedDataFrame(frame);
            return;
        }

        // Append body data first
        byte[] frameData = frame.getFrameData();
        int offset = frame.payloadActualOffset;
        appendBody(frameData, offset, len);

        // a. End stream: submit request (non-streaming) + restore connection-level WU
        if (frame.isEndStream()) {
            handleRequest(true);
            // Return consumed body bytes to connection-level window
            if (bodyData.length > 0) {
                reader.sendConnectionWindowUpdate(ctx, bodyData.length);
            }
            return;
        }

        // b. recvWindow > 0: only check connection-level WU
        if (receiveWindow > 0) {
            restoreConnectionWindow();
            return;
        }

        // c. recvWindow < 0
        if (receiveWindow < 0) {
            // Protocol violation: client sent beyond window
            reader.sendRstStreamFrame(ctx, streamId, 3);
            reader.removeStream(streamId);
            return;
        }

        // d. recvWindow == 0
        if (bodyData.length < MAX_STREAM_CAPACITY_SIZE) {
            // First window exhaustion: send WU to refill
            // newWindowSize = MAX_STREAM_CAPACITY_SIZE - bodyData.length (remaining buffer space)
            int newWindowSize = MAX_STREAM_CAPACITY_SIZE - bodyData.length;
            reader.sendWindowUpdatePair(ctx, this, newWindowSize);
            receiveWindow = newWindowSize;
            restoreConnectionWindow();
        } else if (bodyData.length == MAX_STREAM_CAPACITY_SIZE) {
            // Second window exhaustion: bodyData at max capacity, start streaming
            needStreaming = true;
            handleRequest(false);
        }
    }

    /**
     * Append DATA frame payload to body.
     * <p>
     * Uses offset and len from FramePayload to skip PADDED flag overhead:
     * - PADDED: first byte is pad length, padding at end (excluded via len)
     * - PRIORITY (HEADERS only): 5 bytes exclusive OR dependency field
     *
     * @param payload DATA frame payload byte array
     * @param offset  start offset of actual data (after PADDED/PRIORITY overhead)
     * @param len     length of actual data (excluding padding at end)
     */
    public void appendBody(byte[] payload, int offset, int len) {
        if (payload == null || len == 0) return;
        byte[] newBody = Arrays.copyOf(bodyData, bodyData.length + len);
        System.arraycopy(payload, offset, newBody, bodyData.length, len);
        bodyData = newBody;
    }

    /**
     * Get headers map.
     */
    public Map<String, Object> headers() {
        return headers;
    }

    /**
     * Get content length.
     */
    public long getContentLength() {
        if (needStreaming) {
            if (declaredContentLength != -1) {
                return declaredContentLength;
            }
            if (bodyStream.ended) {
                return bodyStream.totalLength;
            }
            throw new IllegalStateException("content-length not declared and bodyStream not ended");
        } else {
            return bodyData.length;
        }
    }

    /**
     * Get accumulated body data. Note: in streaming mode, this only returns
     * initial data before streaming started, not the complete body.
     * Use bodyStream to read complete data in streaming mode.
     */
    public byte[] getBodyData() {
        if (needStreaming) {
            throw new IllegalStateException("bodyData not available in streaming mode");
        }
        return bodyData;
    }

    void handleRequest(boolean endStream) {
        // Only create bodyStream for streaming (endStream=false); endStream=true handled in onHeadersFrame
        if (!(this.endStream = endStream)) {
            this.bodyStream = new Http2BodyInputStream(bodyData, this);
        }
        submitRequest();
    }

    /**
     * Mark this stream as handed over to internal logic (e.g. proxy forwarding).
     * The framework will skip normal stream cleanup after handler returns.
     */
    void handover() {
        this.handovered = true;
    }

    /**
     * Restore connection-level window if below stream-level window.
     */
    void restoreConnectionWindow() throws IOException {
        if (reader.connectRecvWindow < receiveWindow) {
            int increment = (int) (receiveWindow - reader.connectRecvWindow);
            reader.sendConnectionWindowUpdate(ctx, increment);
        }
    }

    void feedDataFrame(Http2Frame frame) throws IOException {
        boolean frameEnd = frame.isEndStream();
        this.endStream = frameEnd;
        if (bodyStream != null) {
            if (!bodyStream.feed(frame.frameData, frame.payloadActualOffset, frame.payloadActualLength)) {
                reader.sendRstStreamFrame(ctx, streamId, 3);
                return;
            }
            if (frameEnd) bodyStream.endStream();
        }
    }

    void completeRequest() throws IOException {
        if (!endStream) {
            reader.sendRstStreamFrame(ctx, streamId, 0);
        }
    }

    /**
     * Clean up stream resources:
     * remove from reader to stop new DATA frames, flush consumed WINDOW_UPDATE.
     * Fallback: if END_STREAM was never sent (e.g. commit omitted), send a terminal frame.
     */
    void completeStream() {
        if (!endStreamSent) {
            try {
                ByteBuffer endFrame = createFrameBuffer(9, 0, Http2Frame.FRAME_TYPE_DATA, Http2Frame.END_STREAM);
                endFrame.flip();
                writeFrame(endFrame);
            } catch (IOException ignore) {
            }
        }
        reader.removeStream(streamId);
        flushConsumedWindowUpdate();
        if (Http2MessageReader.DEBUG)
            System.out.println("\u001B[32m[" + debugPrefix() + "] streamId=" + streamId + " complete " + requestUri + "\u001B[0m");
    }

    /**
     * Flush unconsumed bytes as connection-level WINDOW_UPDATE before stream is removed.
     * <p>
     * In streaming mode, bytes fed into the body stream via {@link #feedDataFrame} have already
     * been deducted from the connection-level receive window, but might not have been consumed
     * by the application handler (e.g., RST_STREAM terminated early, or handler returned without
     * draining the stream). This method recovers those bytes at the connection level.
     * <p>
     * Stream-level WU is NOT sent — the stream is being removed, so stream-level window is moot.
     * Only connection-level WU (streamId=0) is issued.
     */
    void flushConsumedWindowUpdate() {
        if (!needStreaming) {
            return;
        }
        long unconsumed = bodyStream.totalLength - bodyStream.getConsumed();
        if (unconsumed > 0) {
            try {
                reader.sendConnectionWindowUpdate(ctx, (int) unconsumed);
            } catch (IOException ignore) { // Best-effort; connection may be closing
            }
        }
    }

    public InputStream getInputStream() {
        if (bodyStream == null) {
            bodyStream = new Http2BodyInputStream(bodyData, this);
        }
        return bodyStream;
    }

    /**
     * Called by Http2BodyInputStream after read() to update WU.
     * Sends WU for consumed bytes and updates both stream and connection level windows.
     */
    void notifyConsumed(int consumed) {
        if (consumed <= 0) return;
        try {
            reader.sendWindowUpdatePair(ctx, this, consumed);
            receiveWindow += consumed;
        } catch (IOException ignore) { // Best-effort; connection may be closing
        }
    }

    // ==================== Outgoing frame helpers ====================

    /**
     * Unified entry point for all outgoing (response) frames.
     * <p>
     * Writes a pre-built frame to the channel. When {@link Http2MessageReader#DEBUG}
     * is enabled, prints frame details to stdout with "[Server]" prefix
     * (vs "[Client]" for incoming frames).
     */
    public void writeFrame(ByteBuffer frame) throws IOException {
        // Reject any frame write after END_STREAM has been sent
        if (endStreamSent) {
            byte[] headerBytes = new byte[9];
            for (int i = 0; i < 9; i++) headerBytes[i] = frame.get(i);
            log.error("[H2] Warning: writeFrame after END_STREAM on stream " + streamId
                    + ", header: " + Utils.printHexString(headerBytes, ' '));
            return;
        }
        int frameFlags = frame.get(4);
        if ((frameFlags & Http2Frame.END_STREAM) != 0) { // Check frame flags (offset 4) for END_STREAM
            endStreamSent = true;
        }

        if (Http2MessageReader.DEBUG) {
            Http2Frame h2Frame = Http2Frame.fromByteBuffer(frame);
            System.out.print("\u001B[32m[" + debugPrefix() + h2Frame.getType() + "] streamId=" + h2Frame.getStreamId() + " length=" + h2Frame.getPayloadLength() + "\u001B[0m");
            if (h2Frame.getType() != Http2FrameType.DATA) {
                System.out.println(h2Frame.toHexDump());
            } else {
                System.out.println();
            }
        }
        ctx.writeFlush(frame);
    }

    void writeDataFrame(ByteBuffer frame, int payloadLength) throws IOException {
        while (sendWindow < payloadLength || reader.connectSendWindow < payloadLength) {
            reader.awaitSendWU();
        }
        sendWindow -= payloadLength;
        synchronized (reader) {
            reader.connectSendWindow -= payloadLength;
        }
        writeFrame(frame);
    }

    /**
     * Pre-fill the 9-byte frame header and return a ByteBuffer positioned at 9.
     *
     * @param capacity total buffer capacity (must be 9 + payload size)
     * @param length   payload length for the 24-bit length field
     * @param type     frame type (e.g. 0x00 for DATA, 0x01 for HEADERS)
     * @param flags    flags byte
     */
    public java.nio.ByteBuffer createFrameBuffer(int capacity, int length, int type, int flags) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(capacity);
        buf.put((byte) (length >> 16))
                .put((byte) (length >> 8))
                .put((byte) length)
                .put((byte) type)
                .put((byte) flags)
                .putInt(streamId);
        return buf;
    }

    // ==================== H2 response encoding (proxy) ====================
    /**
     * Max payload per DATA frame for sending, limited by both the client's
     * SETTINGS_MAX_FRAME_SIZE and the local channel write buffer capacity.
     */
    public int sendChunkSize() {
        return Math.min(reader.maxSendPayloadSize, ctx.getWriteBufferSize() - 9);
    }

    /**
     * HPACK encode response headers (status + regular headers).
     * Skips H1-specific headers (transfer-encoding, content-length, connection, keep-alive)
     * that are not valid in HTTP/2.
     */
    byte[] encodeHpackHeaders(HttpDecodedResponse response) {
        HttpBuf buf = HttpBuf.of(256);
        writeHpackLiteral(buf, ":status", String.valueOf(response.getStatusCode())); // :status pseudo-header
        Map<String, Object> headers = response.getHeaders(); // Regular headers, skip H1-specific headers
        if (headers != null) {
            for (Map.Entry<String, Object> entry : headers.entrySet()) {
                String name = entry.getKey().toLowerCase();
                if (HttpHeaderNames.TRANSFER_ENCODING.equals(name) || HttpHeaderNames.CONTENT_LENGTH.equals(name)
                        || HttpHeaderNames.CONNECTION.equals(name) || HttpHeaderNames.KEEP_ALIVE.equals(name)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof List) {
                    for (String v : (List<String>) value) {
                        writeHpackLiteral(buf, name, v);
                    }
                } else if (value != null) {
                    writeHpackLiteral(buf, name, String.valueOf(value));
                }
            }
        }

        return buf.toBytes();
    }

    void writeHpackLiteral(HttpBuf buf, String name, String value) {
        buf.write((byte) 0x10); // never-indexed literal header field
        writeHpackString(buf, name);
        writeHpackString(buf, value);
    }

    // ==================== HPACK utility (shared with Http2Response) ====================
    void writeHpackString(HttpBuf buf, String value) {
        byte[] bytes = value.getBytes(Utils.UTF_8);
        synchronized (_HPACK_TMP) {
            int prefixLen = Http2HpackCodec.encodeLength(bytes.length, _HPACK_TMP, 0, false);
            buf.write(_HPACK_TMP, 0, prefixLen);
        }
        buf.write(bytes);
    }

}
