package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * <p> HTTP/2 client-side frame decoder. </p>
 * <p> Sends client preface, receives H2 frames from the server, dispatches to stream contexts. </p>
 *
 * @author wangyc
 */
public class Http2ClientReader extends Http2MessageReader {

    /**
     * Client preface settings: no push, default initial window, large header table.
     */
    static final byte[] INIT_CLIENT_SETTINGS;

    static {
        int initialRecvWindow = INITIAL_RECEIVE_WINDOW_SIZE;
        // Build SETTINGS frame: SETTINGS_ENABLE_PUSH(0) + SETTINGS_INITIAL_WINDOW_SIZE = 21 bytes
        // id=2 (ENABLE_PUSH), value=0 (disabled)
        // id=4 (INITIAL_WINDOW_SIZE), value=initialRecvWindow
        INIT_CLIENT_SETTINGS = new byte[]{
                0, 0, 12, 4, 0, 0, 0, 0, 0,
                0, 2,
                0, 0, 0, 0,   // ENABLE_PUSH = 0
                0, 4,
                (byte) (initialRecvWindow >> 24), (byte) (initialRecvWindow >> 16),
                (byte) (initialRecvWindow >> 8), (byte) initialRecvWindow
        };
    }

    /**
     * Next client-initiated stream ID (odd, starts at 1, increments by 2).
     */
    private int nextStreamId = 1;

    public Http2ClientReader() {
    }

    /**
     * Allocate the next stream ID for a client-initiated request.
     *
     * @return odd stream ID
     */
    public int nextStreamId() {
        int id = nextStreamId;
        nextStreamId += 2;
        return id;
    }

    // ==================== Handshake ====================

    /**
     * Client H2 handshake: send preface + SETTINGS, wait for server SETTINGS + ACK.
     */
    @Override
    public void init(ChannelContext ctx) throws Exception {
        try {
            // 1. Send client preface magic
            ctx.writeFlush(CLIENT_CONNECTION_PREFACE);
            // 2. Send client SETTINGS
            ctx.writeFlush(INIT_CLIENT_SETTINGS);
        } catch (IOException e) {
            ctx.close();
            throw e;
        }
    }

    // ==================== Stream context management ====================

    /**
     * Get or create stream for the given stream ID.
     * Client accepts both odd (client-initiated) and even (server-push) stream IDs.
     */
    @Override
    protected Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException {
        Http2Stream stream = streamMap.get(streamId);
        if (stream == null && streamId > currentMaxStreamId) {
            stream = new Http2ClientStream(this, streamId, ctx);
            streamMap.put(streamId, stream);
            currentMaxStreamId = streamId;
        }
        return stream;
    }
}
