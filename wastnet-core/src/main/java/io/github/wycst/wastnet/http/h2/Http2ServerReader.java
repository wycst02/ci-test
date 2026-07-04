package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * <p> HTTP/2 server-side frame decoder. </p>
 * <p> Receives H2 frames from the client, dispatches them to stream contexts. </p>
 *
 * @author wangyc
 */
public class Http2ServerReader extends Http2MessageReader {

    /**
     * Server-side max concurrent streams limit ({@link Integer#MAX_VALUE} = unlimited).
     * Configure via {@code -Dwastnet.http2.max-concurrent-streams=N}.
     * <p>
     * Recommended to set in production (e.g. 1024) to prevent resource exhaustion
     * from excessive concurrent streams (MadeYouReset / Rapid Reset attacks).
     */
    static final int MAX_SERVER_CONCURRENT_STREAMS;

    // Server preface settings
    static final byte[] INIT_SERVER_SETTINGS;

    static {
        MAX_SERVER_CONCURRENT_STREAMS = Integer.getInteger("wastnet.http2.max-concurrent-streams", Integer.MAX_VALUE);
        // Build SETTINGS frame with MAX_CONCURRENT_STREAMS(id=3) + INITIAL_WINDOW_SIZE(id=4) = 21 bytes
        INIT_SERVER_SETTINGS = new byte[]{
                0, 0, 12, 4, 0, 0, 0, 0, 0,
                0, 3,
                (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 24), (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 16), (byte) (MAX_SERVER_CONCURRENT_STREAMS >> 8), (byte) MAX_SERVER_CONCURRENT_STREAMS,
                0, 4,
                (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 24), (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 16), (byte) (INITIAL_RECEIVE_WINDOW_SIZE >> 8), (byte) INITIAL_RECEIVE_WINDOW_SIZE
        };
    }

    public Http2ServerReader() {
    }

    // ==================== Handshake ====================

    /**
     * <p> Handle PREFACE and Settings Frame </p>
     */
    @Override
    public void init(ChannelContext ctx) throws Exception {
        try {
            receiveClientPreface(ctx);
            replyServerSettings(ctx);
        } catch (IOException e) {
            ctx.close();
            throw e;
        }
    }

    /**
     * receive client preface
     */
    private void receiveClientPreface(ChannelContext ctx) throws IOException {
        byte[] buf = new byte[PREFACE_MAGIC_LEN];
        int len = ctx.readFully(buf);
        if (len == -1) {
            valid = false;
            ctx.close();
        } else {
            valid = validatePreface(buf);
            if (!valid) {
                ctx.close();
            }
        }
    }

    public Http2ServerReader replyServerSettings(ChannelContext ctx) throws IOException {
        ctx.writeFlush(INIT_SERVER_SETTINGS);
        return this;
    }

    // ==================== Stream context management ====================

    /**
     * Get or create stream for the given stream ID (server accepts odd IDs only).
     */
    @Override
    protected Http2Stream getStream(int streamId, ChannelContext ctx) throws IOException {
        Http2Stream stream = streamMap.get(streamId);
        if (stream == null && streamId > currentMaxStreamId && (streamId & 1) == 1) {
            if (streamMap.size() >= MAX_SERVER_CONCURRENT_STREAMS) {
                sendRstStreamFrame(ctx, streamId, 7); // REFUSED_STREAM
                return null;
            }
            stream = new Http2ServerStream(this, streamId, ctx);
            streamMap.put(streamId, stream);
            currentMaxStreamId = streamId;
        }
        return stream;
    }
}
