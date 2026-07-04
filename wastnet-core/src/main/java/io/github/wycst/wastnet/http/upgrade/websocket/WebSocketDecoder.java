package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpMessageDecoder;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stateless WebSocket frame decoder for RFC 6455.
 *
 * <p>Parses frame headers (FIN, opcode, mask, extended length), decodes
 * payload, and dispatches decoded frames to {@link ChannelContext#invokeHandle}.
 * Supports three continuation strategies: MERGE (default), BATCH, STREAM.
 *
 * <p>This decoder is stateless — all per-connection state (strategy, max payload
 * size) is read from the {@link UpgradeWebSocketHolder} bound to the context.
 */
public class WebSocketDecoder extends HttpMessageDecoder {

    @Override
    public void decode(byte[] buf, int offset, int len, ChannelContext ctx) throws IOException {
        final UpgradeWebSocketHolder holder = (UpgradeWebSocketHolder) ctx.binding();
        final int maxPayloadSize = holder != null ? holder.resource.getMaxPayloadSize() : HttpConf.MAX_WS_FRAME_SIZE;
        final WebSocketResource.ContinuationStrategy strategy = holder != null ? holder.resource.getContinuationStrategy() : WebSocketResource.ContinuationStrategy.MERGE;

        int i = offset, rem = len;
        WebSocketFrame targetFrame = null;
        do {
            long payloadLenLong;
            int maskOffset = i + 2;
            if (rem < 6) {
                buf = read(ctx, buf, i + rem, 6 - rem);
                rem = 6;
            }
            byte firSign = buf[i], secSign = buf[i + 1];
            boolean fin = firSign < 0; // ==> fin & 0x80 != 0
            final int opcode = firSign & 0x0F;
            if ((secSign & 0x80) == 0) { // Protocol error: client frames must include mask (RFC 6455 §5.1)
                handleWebSocketError(ctx, 1002);
                return;
            }
            int baseLen = secSign & 0x7F;
            if (baseLen < 126) {
                payloadLenLong = baseLen;
                rem -= 6;
            } else if (baseLen == 126) { // 126 -> continue read 2 bytes for len
                if (rem < 8) {
                    buf = read(ctx, buf, i + rem, 8 - rem);
                    rem = 8;
                }
                payloadLenLong = ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
                maskOffset += 2;
                rem -= 8;
            } else { // 127 -> continue read 8 bytes for len
                if (rem < 14) {
                    buf = read(ctx, buf, i + rem, 14 - rem);
                    rem = 14;
                }
                payloadLenLong = ((buf[i + 2] & 0xFFL) << 56) |
                        ((buf[i + 3] & 0xFFL) << 48) |
                        ((buf[i + 4] & 0xFFL) << 40) |
                        ((buf[i + 5] & 0xFFL) << 32) |
                        ((buf[i + 6] & 0xFFL) << 24) |
                        ((buf[i + 7] & 0xFFL) << 16) |
                        ((buf[i + 8] & 0xFFL) << 8) |
                        (buf[i + 9] & 0xFFL);
                maskOffset += 8;
                rem -= 14;
            }
            // Unified max-payload check across all three length encodings (RFC 6455 §5.2)
            if (payloadLenLong > maxPayloadSize) {
                handleWebSocketError(ctx, 1009); // Message Too Big
                return;
            }
            int payloadLen = (int) payloadLenLong;
            int payloadOffset = maskOffset + 4;
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0) {
                if (rem >= payloadLen) {
                    System.arraycopy(buf, payloadOffset, payload, 0, payloadLen);
                    rem -= payloadLen;
                } else {
                    if (rem > 0) {
                        System.arraycopy(buf, payloadOffset, payload, 0, rem);
                    }
                    readInternal(ctx, payload, rem, payloadLen - rem);
                    rem = 0;
                }
                unmask(buf, payload, maskOffset);
            }
            WebSocketFrame frame = new WebSocketFrame(WebSocketFrame.FrameType.valueOf(opcode), payload, fin);

            if (strategy == WebSocketResource.ContinuationStrategy.STREAM) { // STREAM: dispatch every frame individually
                ctx.invokeHandle(frame);
                if (rem == 0) return;
                i = payloadOffset + payloadLen;
                continue;
            }
            if (targetFrame == null) { // BATCH / MERGE: accumulate continuation frames
                if (opcode == 0x0) { // Protocol error: first frame cannot be continuation frame
                    handleWebSocketError(ctx, 1002);
                    return;
                }
                targetFrame = frame;
            } else {
                if (opcode != 0x0) { // Protocol error: the next must be continuation frame
                    if (opcode >= 0x8 && fin) { // Control frames allowed between continuation frames (RFC 6455 §5.4)
                        ctx.invokeHandle(frame);
                        if (rem == 0) return;
                        i = payloadOffset + payloadLen;
                        continue;
                    }
                    handleWebSocketError(ctx, 1002);
                    return;
                }
                long mergeLength = ((long) targetFrame.getData().length) + payload.length;
                if (mergeLength > maxPayloadSize) {
                    if (strategy == WebSocketResource.ContinuationStrategy.BATCH) {
                        // Flush intermediate accumulated frame (always fin=false), start new batch with current payload
                        ctx.invokeHandle(targetFrame);
                        targetFrame = frame;
                    } else {
                        handleWebSocketError(ctx, 1009); // Message Too Big
                        return;
                    }
                } else {
                    targetFrame = targetFrame.merge(frame, fin);
                }
            }
            if (fin) {
                ctx.invokeHandle(targetFrame);
                targetFrame = null;
                if (rem == 0) return;
            }
            i = rem == 0 ? 0 : payloadOffset + payloadLen;
        } while (true);
    }

    /**
     * Unmask WebSocket frame payload with 8-byte batch XOR.
     *
     * <p>Reads 4 mask bytes from {@code src[maskOffset..maskOffset+3]} and XORs them into
     * every 8-byte block of {@code dst}, with switch fallthrough for the final 1-7 bytes.
     *
     * <p>Two paths selected at class-load: JDK 9+ uses {@link ByteBuffer#getLong}/{@link ByteBuffer#putLong}
     * with native byte order (JIT-compiled to SSE2/AVX movq+pxor); JDK 8 uses an unrolled byte loop.
     *
     * @param src        source buffer containing the 4 mask bytes
     * @param dst        destination payload array to unmask in-place
     * @param maskOffset position of the 4 mask bytes in {@code src}
     */
    private void unmask(byte[] src, byte[] dst, int maskOffset) {
        byte m0 = src[maskOffset], m1 = src[maskOffset + 1], m2 = src[maskOffset + 2], m3 = src[maskOffset + 3];
        int len = dst.length, aligned = len & ~7;
        if (RuntimeEnv.JDK9PLUS) {
            int mask32 = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder()).getInt(maskOffset);
            long mask64 = ((long) mask32 << 32) | (mask32 & 0xFFFFFFFFL);
            ByteBuffer bb = ByteBuffer.wrap(dst).order(ByteOrder.nativeOrder());
            for (int i = 0; i < aligned; i += 8) {
                bb.putLong(i, bb.getLong(i) ^ mask64);
            }
        } else {
            for (int i = 0; i < aligned; i += 8) {
                dst[i]     ^= m0; dst[i + 1] ^= m1;
                dst[i + 2] ^= m2; dst[i + 3] ^= m3;
                dst[i + 4] ^= m0; dst[i + 5] ^= m1;
                dst[i + 6] ^= m2; dst[i + 7] ^= m3;
            }
        }
        switch (len & 7) {
            case 7: dst[aligned + 6] ^= m2;
            case 6: dst[aligned + 5] ^= m1;
            case 5: dst[aligned + 4] ^= m0;
            case 4: dst[aligned + 3] ^= m3;
            case 3: dst[aligned + 2] ^= m2;
            case 2: dst[aligned + 1] ^= m1;
            case 1: dst[aligned]     ^= m0;
            default:
        }
    }

    private void handleWebSocketError(ChannelContext ctx, int errorCode) {
        String errorMessage = getWebSocketErrorMessage(errorCode);
        WebSocketUtils.sendCloseFrame(ctx, errorCode, errorMessage);
        ctx.close();
    }

    private String getWebSocketErrorMessage(int errorCode) {
        switch (errorCode) {
            case 1002:
                return "Protocol Error: Invalid WebSocket frame format - client frames must include mask";
            case 1009:
                return "Message Too Big: Frame size exceeds maximum limit of " + (HttpConf.MAX_WS_FRAME_SIZE / (1024 * 1024)) + "MB";
            default:
                return "WebSocket Error: Code " + errorCode;
        }
    }
}
