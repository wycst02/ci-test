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

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.reader.HttpMessageReader;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * <p> HTTP/2 frame reader base class (shared by server and client). </p>
 * <p> Provides byte-level frame parsing, window management, and common frame sending. </p>
 *
 * @author wangyc
 */
public abstract class Http2MessageReader extends HttpMessageReader<HttpMessage> {

    /** HTTP/2 connection preface length (RFC 7540 §3.5) */
    static final int PREFACE_MAGIC_LEN = 24;

    /**
     * Client connection preface magic bytes (RFC 7540 §3.5, RFC 9113 §3.5).
     * <p>
     * ASCII representation:
     * {@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}
     */
    static final byte[] CLIENT_CONNECTION_PREFACE = {
            0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32,
            0x2E, 0x30, 0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A
    };

    // Pre-cached 3 little-endian longs for O(1) preface validation
    private static final long PREFACE_LONG_0;
    private static final long PREFACE_LONG_1;
    private static final long PREFACE_LONG_2;

    static {
        byte[] magic = {0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50, 0x2F, 0x32, 0x2E, 0x30, 0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A};
        ByteBuffer buf = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN);
        PREFACE_LONG_0 = buf.getLong();
        PREFACE_LONG_1 = buf.getLong();
        PREFACE_LONG_2 = buf.getLong();
    }

    /**
     * Default DATA frame payload size (RFC 7540 §6.5.2)
     */
    static final int MAX_DATA_PAYLOAD_SIZE = 16384;

    // Default initial send window (65535), used before remote SETTINGS arrives
    static final int INITIAL_RECEIVE_WINDOW_SIZE; // 65535
    static final int INITIAL_SEND_WINDOW_SIZE = 0xFFFF;
    /**
     * Connection-level window size (INITIAL_RECEIVE_WINDOW_SIZE * 16 = 1MB)
     */
    static final int CONNECT_RECEIVE_WINDOW_SIZE;

    // SETTINGS ACK (9 bytes) shared by both sides
    static final byte[] SETTINGS_ACK = {0, 0, 0, 4, 1, 0, 0, 0, 0};

    static {
        int initialSendWindowSize = Integer.getInteger("wastnet.http2.initial.send-window-size", 0xFFFF);
        if (initialSendWindowSize <= 0) {
            initialSendWindowSize = 0xFFFF;
        }
        INITIAL_RECEIVE_WINDOW_SIZE = initialSendWindowSize;
        CONNECT_RECEIVE_WINDOW_SIZE = INITIAL_RECEIVE_WINDOW_SIZE << 4;
    }

    /**
     * Debug switch: enable by -Dwastnet.http2.debug=true
     */
    static final boolean DEBUG = Boolean.getBoolean("wastnet.http2.debug");

    /**
     * Remote-advertised initial stream window size, starts at default 65535.
     * Updated when remote sends SETTINGS with SETTINGS_INITIAL_WINDOW_SIZE.
     */
    int initialSendWindowSize = INITIAL_SEND_WINDOW_SIZE;

    /**
     * Max local HPACK encoder dynamic table size advertised by the remote peer
     * via SETTINGS_HEADER_TABLE_SIZE. Default 4096 per RFC 7541 §4.1.
     * The local HPACK encoder MUST NOT use a dynamic table larger than this
     * value when encoding headers.
     */
    int maxHpackEncoderTableSize = 4096;

    /**
     * Remote-advertised max concurrent streams.
     * For server: client limits server push streams.
     * For client: server limits client-initiated streams.
     */
    int remoteMaxConcurrentStreams;

    /**
     * Max frame payload size for sending, advertised by the remote peer via SETTINGS_MAX_FRAME_SIZE.
     * Default 16384 per RFC 7540 §6.5.2.
     */
    int maxSendPayloadSize = MAX_DATA_PAYLOAD_SIZE;

    final Http2HpackCodec http2HpackCodec = new Http2HpackCodec();
    final byte[] frameStart = new byte[9];

    boolean valid = true;
    int currentMaxStreamId;
    /**
     * Connection-level send window (grants from remote peer)
     */
    long connectSendWindow = CONNECT_RECEIVE_WINDOW_SIZE;
    /**
     * Connection-level receive window
     */
    long connectRecvWindow = CONNECT_RECEIVE_WINDOW_SIZE;
    final Map<Integer, Http2Stream> streamMap = new HashMap<Integer, Http2Stream>();

    // Pre-allocated WINDOW_UPDATE frame buffer (13 bytes: 9 header + 4 payload)
    final ByteBuffer windowUpdateFrame = ByteBuffer.allocate(13);
    // Pre-allocated RST_STREAM frame buffer (13 bytes)
    final ByteBuffer rstStreamFrame = ByteBuffer.allocate(13);
    // Pre-allocated GOAWAY frame buffer (17 bytes: 9 header + 4 last-stream-id + 4 error-code)
    final ByteBuffer goawayFrame = ByteBuffer.allocate(17);

    public Http2MessageReader() {
        windowUpdateFrame.put(2, (byte) 4); // payload length
        windowUpdateFrame.put(3, Http2Frame.FRAME_TYPE_WINDOW_UPDATE);
        rstStreamFrame.put(2, (byte) 4); // payload length
        rstStreamFrame.put(3, Http2Frame.FRAME_TYPE_RST_STREAM);
        goawayFrame.put(2, (byte) 8); // payload length = 8
        goawayFrame.put(3, Http2Frame.FRAME_TYPE_GOAWAY);
    }

    // ==================== Big-endian byte reading utilities ====================

    /**
     * Read 3-byte big-endian unsigned int (used for frame length)
     */
    static int readUInt24(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
    }

    /**
     * Read 4-byte big-endian int (used for streamId, settings values)
     */
    static int readInt32(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }

    /**
     * Read 2-byte big-endian unsigned int (used for settings ids)
     */
    static int readUInt16(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    // ==================== Abstract lifecycle ====================

    /**
     * Initialize the H2 session (preface exchange, settings).
     * Server: wait for client preface.
     * Client: send client preface + settings.
     */
    @Override
    public abstract void init(ChannelContext ctx) throws Exception;

    // ==================== Frame reading ====================
    /**
     * Read and parse HTTP/2 frame from buffer.
     * <p>
     * Frame structure: 9-byte header + payload
     */
    private Http2Frame readNextMessageFrame(ChannelContext ctx, final byte[] buf, final int off, final int len) throws IOException {
        Http2Frame http2MessageFrame = new Http2Frame();
        int length = readUInt24(buf, off);
        int type = buf[off + 3];
        int flags = buf[off + 4];
        int streamId = readInt32(buf, off + 5);
        if (streamId < 0) {
            closeConnection(ctx);
            return null;
        }
        http2MessageFrame.setPayloadLength(length);

        final Http2FrameType frameType = Http2FrameType.valueOf(type);
        http2MessageFrame.setType(frameType);
        http2MessageFrame.setFlags(flags);
        http2MessageFrame.setStreamId(streamId);
        if (length > MAX_DATA_PAYLOAD_SIZE) {
            closeConnection(ctx);
            return null;
        }
        final int frameLength = 9 + length;
        byte[] frameData = new byte[frameLength];
        if (len >= frameLength) {
            System.arraycopy(buf, off, frameData, 0, frameLength);
        } else {
            System.arraycopy(buf, off, frameData, 0, len);
            readInternal(ctx, frameData, len, frameLength - len);
        }

        int payloadOffset = 9, payloadLength = length;
        if (frameType == Http2FrameType.HEADERS && (flags & Http2Frame.PRIORITY) == Http2Frame.PRIORITY) {
            payloadOffset += 5;
            payloadLength -= 5;
        }
        if (type <= Http2Frame.FRAME_TYPE_HEADERS && (flags & Http2Frame.PADDED) == Http2Frame.PADDED) {
            payloadOffset += 1;
            payloadLength -= 1 + (frameData[9] & 0xFF);
        }
        http2MessageFrame.setFrameData(frameData);
        http2MessageFrame.payload(payloadOffset, payloadLength);
        return http2MessageFrame;
    }

    // ==================== Main decode loop ====================

    /**
     * Decode and process HTTP/2 frames from the channel.
     */
    @Override
    public void decode(ChannelContext ctx, byte[] buf, int offset, int len) {
        while (valid) {
            try {
                if (len < 9) {
                    buf = read(ctx, buf, offset + len, 9 - len);
                    len = 9;
                }
                Http2Frame frame = readNextMessageFrame(ctx, buf, offset, len);
                if (frame == null) {
                    return;
                }
                handleFrame(ctx, frame);
                int frameLength = frame.getFrameLength();
                offset += frameLength;
                len -= frameLength;
                if (len < 1) return;
            } catch (IOException e) {
                closeConnection(ctx);
                return;
            }
        }
    }
    // ==================== Frame dispatch ====================
    /**
     * Handle HTTP/2 frame based on frame type.
     */
    final void handleFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        if (DEBUG) {
            synchronized (this) {
                System.out.print("\n\u001B[34m[Client " + frame.getType() + "] streamId=" + frame.getStreamId() + " length=" + frame.getPayloadLength() + "\u001B[0m");
                if (frame.getType() != Http2FrameType.DATA) {
                    System.out.println(frame.toHexDump());
                }
            }
        }
        final int streamId = frame.getStreamId();
        if (streamId == 0) {
            handleControlFrame(ctx, frame);
            return;
        }
        Http2Stream stream = getStream(streamId, ctx);
        if (stream == null) return;
        stream.handleFrame(frame, ctx);
    }

    // ==================== Control frame handling ====================

    /**
     * Handle a control frame received on stream 0.
     */
    private void handleControlFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        switch (frame.getType()) {
            case SETTINGS:
                handleSettingsFrame(ctx, frame);
                return;
            case PING:
                frame.getFrameData()[4] |= (byte) Http2Frame.PING_ACK;
                ctx.writeFlush(frame.getFrameData());
                return;
            case WINDOW_UPDATE: {
                int increment = ByteBuffer.wrap(frame.getFrameData()).getInt(9);
                if ((connectSendWindow += increment) > Integer.MAX_VALUE || increment < 0) {
                    closeConnection(ctx);
                }
                wakeupSendWU();
                return;
            }
            case PRIORITY:
                return;
            default:
                // GOAWAY, PUSH_PROMISE, RST_STREAM on stream 0, etc.
                closeConnection(ctx);
        }
    }

    /**
     * Parse and apply SETTINGS frame from the remote peer.
     */
    private void handleSettingsFrame(ChannelContext ctx, Http2Frame frame) throws IOException {
        // Ignore SETTINGS ACK
        if ((frame.getFlags() & Http2Frame.SETTINGS_ACK) != 0) return;

        byte[] data = frame.getFrameData();
        int offset = frame.getPayloadActualOffset();
        int payloadLen = frame.getPayloadLength();
        int newInitialWindow = -1;

        try {
            for (int i = 0; i < payloadLen; i += 6) {
                int id = readUInt16(data, offset + i);
                int value = readInt32(data, offset + i + 2);
                switch (id) {
                    case 1: // SETTINGS_HEADER_TABLE_SIZE
                        maxHpackEncoderTableSize = value;
                        http2HpackCodec.setMaxHeaderSize(maxHpackEncoderTableSize);
                        break;
                    case 3: // SETTINGS_MAX_CONCURRENT_STREAMS
                        remoteMaxConcurrentStreams = value;
                        break;
                    case 4: // SETTINGS_INITIAL_WINDOW_SIZE
                        if (value < 0) {
                            closeConnection(ctx);
                            return;
                        }
                        newInitialWindow = value;
                        break;
                    case 5: // SETTINGS_MAX_FRAME_SIZE
                        if (value < MAX_DATA_PAYLOAD_SIZE || value > 16777215) {
                            closeConnection(ctx);
                            return;
                        }
                        maxSendPayloadSize = value;
                        break;
                    default:
                        break;
                }
            }

            // Only update the initial window for new streams; existing streams keep their current window.
            if (newInitialWindow >= 0 && newInitialWindow != initialSendWindowSize) {
                initialSendWindowSize = newInitialWindow;
            }

            // Send SETTINGS ACK
            ctx.writeFlush(ByteBuffer.wrap(SETTINGS_ACK));
        } catch (Exception e) {
            closeConnection(ctx);
        }
    }

    // ==================== Frame sending ====================

    void sendConnectionWindowUpdate(ChannelContext ctx, int windowLen) throws IOException {
        synchronized (windowUpdateFrame) {
            windowUpdateFrame.putLong(5, windowLen).clear();
            ctx.writeFlush(windowUpdateFrame);
            connectRecvWindow += windowLen;
        }
    }

    /**
     * Batch send stream + connection WINDOW_UPDATE frames in one TCP segment.
     */
    void sendWindowUpdatePair(ChannelContext ctx, Http2Stream streamCtx, int windowLen) throws IOException {
        ByteBuffer wuFrames = ByteBuffer.allocate(26);
        synchronized (windowUpdateFrame) {
            windowUpdateFrame.putInt(9, windowLen);
            wuFrames.put(windowUpdateFrame.array(), 0, 13).put(windowUpdateFrame.array(), 0, 13);
            connectRecvWindow += windowLen;
        }
        wuFrames.putInt(5, 0).putInt(18, streamCtx.streamId).flip();
        ctx.writeFlush(wuFrames);
    }

    /**
     * Send RST_STREAM frame to reset a stream.
     */
    void sendRstStreamFrame(ChannelContext ctx, int streamId, int errorCode) throws IOException {
        synchronized (rstStreamFrame) {
            rstStreamFrame.putInt(5, streamId).putInt(9, errorCode).flip();
            ctx.writeFlush(rstStreamFrame);
        }
    }

    /**
     * Send GOAWAY frame to gracefully close the connection.
     */
    void sendGoawayFrame(ChannelContext ctx, int lastStreamId, int errorCode) throws IOException {
        synchronized (goawayFrame) {
            goawayFrame.putInt(9, lastStreamId).putInt(13, errorCode).flip();
            ctx.writeFlush(goawayFrame);
        }
    }

    // ==================== Stream management ====================

    void removeStream(int streamId) {
        streamMap.remove(streamId);
    }

    /**
     * Get or create stream for the given stream ID.
     */
    protected abstract Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException;

    // ==================== Window wakeup ====================

    void awaitSendWU() {
        try {
            synchronized (this) {
                wait(100); // wait WU
            }
        } catch (InterruptedException ignored) {
        }
    }

    void wakeupSendWU() {
        synchronized (this) {
            notifyAll();
        }
    }

    // ==================== Utility ====================

    /**
     * Validate client preface magic string.
     */
    public static boolean validatePreface(byte[] buf) {
        if (buf.length != PREFACE_MAGIC_LEN) return false;
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        return bb.getLong() == PREFACE_LONG_0
            && bb.getLong() == PREFACE_LONG_1
            && bb.getLong() == PREFACE_LONG_2;
    }

    void closeConnection(ChannelContext ctx) {
        valid = false;
        ctx.close();
    }
}
