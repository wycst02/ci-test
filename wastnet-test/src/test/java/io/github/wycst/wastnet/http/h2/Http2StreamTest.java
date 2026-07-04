package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Stream}.
 *
 * @author wangyc
 */
public class Http2StreamTest {

    @Test
    public void testCreateFrameBuffer() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ByteBuffer buf = ctx.createFrameBuffer(100, 50, 0x00, 0x01);
        Assertions.assertNotNull(buf);
        Assertions.assertEquals(100, buf.capacity());
        byte[] array = buf.array();
        Assertions.assertEquals(0x00, array[3] & 0xFF); // Type = DATA
        Assertions.assertEquals(0x01, array[4] & 0xFF); // Flags = END_STREAM
        Assertions.assertEquals(1, array[8]); // Stream ID = 1
    }

    @Test
    public void testCreateFrameBufferForHeaders() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 3, mockCtx);

        ByteBuffer buf = ctx.createFrameBuffer(50, 20, 0x01, 0x04);
        byte[] array = buf.array();
        Assertions.assertEquals(0x01, array[3] & 0xFF); // Type = HEADERS
        Assertions.assertEquals(0x04, array[4] & 0xFF); // Flags = END_HEADERS
        Assertions.assertEquals(3, array[8]); // Stream ID = 3
    }

    // ==================== writeHpackString ====================

    @Test
    public void testWriteHpackString() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        HttpBuf buf = HttpBuf.of(32);
        ctx.writeHpackString(buf, "test");
        byte[] bytes = buf.toBytes();
        Assertions.assertEquals(4, bytes[0] & 0x7F); // length prefix
        Assertions.assertEquals('t', bytes[1]);
        Assertions.assertEquals('e', bytes[2]);
        Assertions.assertEquals('s', bytes[3]);
        Assertions.assertEquals('t', bytes[4]);
    }

    // ==================== writeHpackLiteral boundary tests ====================

    private static String stringOfLen(int len) {
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = 'A';
        return new String(chars);
    }

    private void assertLiteralHeader(HttpBuf buf, String name, String value) {
        byte[] data = buf.toBytes();
        // First byte: literal flag (0x10)
        Assertions.assertEquals(0x10, data[0] & 0xFF, "missing literal flag");

        int off = 1;
        // Decode HPACK string: name
        int nameLen = data[off] & 0x7F;
        off++;
        if (nameLen == 127) {
            // Read varint continuation: value = 127 + continuation
            int temp = 0;
            int shift = 0;
            while (true) {
                int b = data[off++] & 0xFF;
                temp += (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            nameLen = 127 + temp;
        }
        Assertions.assertEquals(name.length(), nameLen, "name length mismatch");
        Assertions.assertEquals(name, new String(data, off, nameLen));
        off += nameLen;

        // Decode HPACK string: value
        int valueLen = data[off] & 0x7F;
        off++;
        if (valueLen == 127) {
            int temp = 0;
            int shift = 0;
            while (true) {
                int b = data[off++] & 0xFF;
                temp += (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            valueLen = 127 + temp;
        }
        Assertions.assertEquals(value.length(), valueLen, "value length mismatch");
        Assertions.assertEquals(value, new String(data, off, valueLen));
    }

    @Test
    public void testWriteHpackLiteralEmptyValue() {
        HttpBuf buf = HttpBuf.of(64);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", "");
        assertLiteralHeader(buf, "x", "");
    }

    @Test
    public void testWriteHpackLiteralShortValue() {
        HttpBuf buf = HttpBuf.of(64);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", "hello");
        assertLiteralHeader(buf, "x", "hello");
    }

    @Test
    public void testWriteHpackLiteralValueLength126() {
        // 126 < 127: single-byte length prefix
        String val = stringOfLen(126);
        HttpBuf buf = HttpBuf.of(256);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength127() {
        // 127 >= 127: multi-byte length prefix, prefix = 127
        String val = stringOfLen(127);
        HttpBuf buf = HttpBuf.of(256);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength128() {
        // 128 >= 127: multi-byte prefix, remainder = 1
        String val = stringOfLen(128);
        HttpBuf buf = HttpBuf.of(256);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength200() {
        // 200 >= 127: multi-byte prefix, remainder = 73
        String val = stringOfLen(200);
        HttpBuf buf = HttpBuf.of(256);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralLongName() {
        // Name also uses writeHpackString, test with long name
        String name = stringOfLen(200);
        HttpBuf buf = HttpBuf.of(512);
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        ctx.writeHpackLiteral(buf, name, "v");
        assertLiteralHeader(buf, name, "v");
    }

    // ==================== writeFrame ====================

    @Test
    public void testWriteFrame() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ByteBuffer frame = ByteBuffer.allocate(50);
        ctx.writeFrame(frame);
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    // ==================== headers ====================

    @Test
    public void testHeadersWithEmptyMapByDefault() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 0, mockCtx);
        Assertions.assertTrue(ctx.headers().isEmpty());
    }

    @Test
    public void testGetContentLengthDefault() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        Assertions.assertEquals(0L, ctx.getContentLength());
    }

    // ==================== appendBody ====================

    @Test
    public void testAppendBodyAccumulatesData() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        byte[] data1 = "hello".getBytes();
        ctx.appendBody(data1, 0, data1.length);
        Assertions.assertArrayEquals(data1, ctx.getBodyData());

        byte[] data2 = " world".getBytes();
        ctx.appendBody(data2, 0, data2.length);
        Assertions.assertArrayEquals("hello world".getBytes(), ctx.getBodyData());
    }

    @Test
    public void testAppendBodyNullPayloadIsNoOp() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.appendBody(null, 0, 10);
        Assertions.assertEquals(0, ctx.getBodyData().length);
    }

    @Test
    public void testAppendBodyEmptyLengthIsNoOp() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        byte[] data = "data".getBytes();
        ctx.appendBody(data, 0, 0);
        Assertions.assertEquals(0, ctx.getBodyData().length);
    }

    // ==================== handover ====================

    @Test
    public void testHandoverSetsFlag() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        Assertions.assertFalse(ctx.handover);
        ctx.handover();
        Assertions.assertTrue(ctx.handover);
    }

    // ==================== getInputStream ====================

    @Test
    public void testGetInputStreamCreatesBodyStream() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        InputStream in = ctx.getInputStream();
        Assertions.assertNotNull(in);
        Assertions.assertTrue(in instanceof Http2BodyInputStream);
        // Second call returns same instance
        Assertions.assertSame(in, ctx.getInputStream());
    }

    // ==================== completeRequest ====================

    @Test
    public void testCompleteRequestSendsRstStreamWhenNotEnded() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStream = false;
        ctx.completeRequest();
        verify(mockReader).sendRstStreamFrame(mockCtx, 1, 0);
    }

    @Test
    public void testCompleteRequestNoOpWhenAlreadyEnded() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStream = true;
        ctx.completeRequest();
        verify(mockReader, never()).sendRstStreamFrame(any(), anyInt(), anyInt());
    }

    // ==================== completeStream ====================

    @Test
    public void testCompleteStreamRemovesStream() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(65535);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStreamSent = true; // skip fallback DATA frame
        ctx.completeStream();
        verify(mockReader).removeStream(1);
    }

    @Test
    public void testCompleteStreamSendsEndStreamIfNotSent() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(65535);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStreamSent = false;
        ctx.completeStream();
        verify(mockCtx).writeFlush(any(ByteBuffer.class));
        verify(mockReader).removeStream(1);
    }

    // ==================== handleFrame dispatch ====================

    @Test
    public void testHandleFrameRstStreamRemovesStream() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        // RST_STREAM frame (9-byte header, no payload)
        ByteBuffer buf = ByteBuffer.allocate(9);
        buf.put(new byte[]{0, 0, 0, 3, 0, 0, 0, 0, 1}); // type=3 RST_STREAM, streamId=1
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);

        ctx.handleFrame(frame, mockCtx);
        verify(mockReader).removeStream(1);
    }

    @Test
    public void testHandleFrameWindowUpdateIncrementsSendWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        mockReader.connectSendWindow = 100000;
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        long before = ctx.sendWindow;
        // WINDOW_UPDATE frame (9-byte header + 4-byte increment)
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 8, 0, 0, 0, 0, 1}); // type=8 WINDOW_UPDATE, streamId=1
        buf.putInt(5000); // increment = 5000
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);

        ctx.handleFrame(frame, mockCtx);
        Assertions.assertEquals(before + 5000, ctx.sendWindow);
    }

    // ==================== restoreConnectionWindow ====================

    @Test
    public void testRestoreConnectionWindowWhenBelowStreamWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.receiveWindow = 30000;
        mockReader.connectRecvWindow = 10000; // below stream window

        ctx.restoreConnectionWindow();
        verify(mockReader).sendConnectionWindowUpdate(mockCtx, 20000);
    }

    @Test
    public void testRestoreConnectionWindowWhenAboveStreamWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.receiveWindow = 10000;
        mockReader.connectRecvWindow = 30000; // above stream window, no restore needed

        ctx.restoreConnectionWindow();
        verify(mockReader, never()).sendConnectionWindowUpdate(any(), anyInt());
    }

    // ==================== sendChunkSize ====================

    @Test
    public void testSendChunkSizeReturnsMinOfLimits() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(20000);
        Http2ServerReader mockReader = mock(Http2ServerReader.class);
        mockReader.maxSendPayloadSize = 16384;
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        int chunkSize = ctx.sendChunkSize();
        // min(16384, 20000 - 9) = 16384
        Assertions.assertEquals(16384, chunkSize);
    }
}
