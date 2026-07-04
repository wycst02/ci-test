package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Coverage tests for Http2Stream. */
class Http2StreamCoverageTest {

    static class Fixture {
        final Http2MessageReader reader;
        final ChannelContext ctx;
        final TestStream stream;
        Fixture(Http2MessageReader reader, ChannelContext ctx, TestStream stream) {
            this.reader = reader; this.ctx = ctx; this.stream = stream;
        }
    }

    private static void setField(Object target, String name, Object val) throws Exception {
        java.lang.reflect.Field f = target.getClass().getSuperclass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private Fixture createFixture() throws Exception {
        Http2MessageReader reader = mock(Http2MessageReader.class);
        setField(reader, "initialSendWindowSize", 65535);
        setField(reader, "maxSendPayloadSize", 16384);
        setField(reader, "connectSendWindow", 65535);
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        return new Fixture(reader, ctx, new TestStream(reader, 1, ctx));
    }

    static class TestStream extends Http2Stream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) { super(reader, sid, ctx); }
        protected void onEndHeaders() {}
        protected void submitRequest() {}
        public String debugPrefix() { return "Test"; }
    }

    // ==================== Basic ====================

    @Test void testHeadersGetter() throws Exception { assertNotNull(createFixture().stream.headers()); }

    @Test void testGetAttrsLazyInit() throws Exception {
        Fixture f = createFixture();
        assertNotNull(f.stream.getAttrs());
        assertSame(f.stream.getAttrs(), f.stream.getAttrs());
    }

    @Test void testAppendBody() throws Exception {
        Fixture f = createFixture();
        f.stream.appendBody("hello".getBytes(), 0, 5);
        assertEquals(5, f.stream.bodyData.length);
    }

    @Test void testAppendBodyNull() throws Exception { createFixture().stream.appendBody(null, 0, 5); }

    @Test void testAppendBodyZeroLen() throws Exception { createFixture().stream.appendBody(new byte[5], 0, 0); }

    @Test void testGetContentLengthNonStreaming() throws Exception {
        Fixture f = createFixture();
        f.stream.bodyData = new byte[]{1, 2, 3};
        assertEquals(3, f.stream.getContentLength());
    }

    @Test void testGetContentLengthStreamingDeclared() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = 200;
        assertEquals(200, f.stream.getContentLength());
    }

    @Test void testGetBodyDataNonStreaming() throws Exception {
        Fixture f = createFixture(); f.stream.bodyData = new byte[]{1, 2};
        assertArrayEquals(new byte[]{1, 2}, f.stream.getBodyData());
    }

    @Test void testGetBodyDataStreamingThrows() throws Exception {
        Fixture f = createFixture(); f.stream.needStreaming = true;
        assertThrows(IllegalStateException.class, () -> f.stream.getBodyData());
    }

    // ==================== Buffer helpers ====================

    @Test void testCreateFrameBuffer() throws Exception {
        assertEquals(9, createFixture().stream.createFrameBuffer(9, 10, 0x01, 0x05).capacity());
    }

    @Test void testSendChunkSize() throws Exception { assertTrue(createFixture().stream.sendChunkSize() > 0); }

    @Test void testCompleteRequestEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.endStream = true;
        f.stream.completeRequest();
    }

    @Test void testCompleteRequestNotEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.completeRequest();
        verify(f.reader).sendRstStreamFrame(any(), anyInt(), anyInt());
    }

    // ==================== HPACK encode ====================

    private static HttpDecodedResponse mockResp(int code, Map<String, Object> headers) {
        HttpDecodedResponse r = mock(HttpDecodedResponse.class);
        when(r.getStatusCode()).thenReturn(code);
        when(r.getHeaders()).thenReturn(headers);
        return r;
    }

    @Test void testEncodeHpackHeaders() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("content-type", "text/plain");
        assertNotNull(createFixture().stream.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersSkipped() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("transfer-encoding", "chunked"); h.put("connection", "keep-alive");
        h.put("keep-alive", "timeout=5"); h.put("content-length", "100");
        assertNotNull(createFixture().stream.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersListValue() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        List<String> v = new ArrayList<String>(); v.add("v1"); v.add("v2");
        h.put("x-custom", v);
        assertNotNull(createFixture().stream.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersNullHeaders() throws Exception {
        assertNotNull(createFixture().stream.encodeHpackHeaders(mockResp(200, null)));
    }

    // ==================== Handle request ====================

    @Test void testHandleRequestEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.handleRequest(true);
        assertTrue(f.stream.endStream);
    }

    @Test void testHandleRequestNotEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.handleRequest(false);
        assertFalse(f.stream.endStream); assertNotNull(f.stream.bodyStream);
    }

    @Test void testGetInputStreamCreatesBodyStream() throws Exception {
        assertNotNull(createFixture().stream.getInputStream());
    }

    // ==================== Control frames ====================

    @Test void testOnHeadersFrameEndHeadersAlreadyTrue() throws Exception {
        Fixture f = createFixture(); f.stream.endHeaders();
        f.stream.onHeadersFrame(new Http2Frame(), f.ctx);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1);
    }

    @Test void testOnDataFrameBeforeEndHeaders() throws Exception {
        Fixture f = createFixture();
        Http2Frame df = new Http2Frame();
        df.payloadActualLength = 10;
        f.stream.onDataFrame(df, f.ctx);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1);
    }

    // ==================== Stream lifecycle ====================

    @Test void testCompleteStream() throws Exception {
        try { createFixture().stream.completeStream(); } catch (Exception ignored) { }
    }

    @Test void testCompleteStreamAlreadySent() throws Exception {
        Fixture f = createFixture(); f.stream.endStreamSent = true;
        f.stream.completeStream();
    }

    @Test void testFlushConsumedWindowUpdateNotStreaming() throws Exception {
        createFixture().stream.flushConsumedWindowUpdate();
    }

    @Test void testNotifyConsumed() throws Exception {
        Fixture f = createFixture(); f.stream.notifyConsumed(100);
        verify(f.reader).sendWindowUpdatePair(f.ctx, f.stream, 100);
    }

    @Test void testNotifyConsumedZero() throws Exception {
        Fixture f = createFixture(); f.stream.notifyConsumed(0);
        verify(f.reader, never()).sendWindowUpdatePair(any(), any(), anyInt());
    }

    @Test void testWriteFrameAfterEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.endStreamSent = true;
        f.stream.writeFrame(java.nio.ByteBuffer.allocate(9));
    }

    // ==================== onDataFrame branch gaps ====================

    @Test void testOnDataFrameEndStreamEmptyBody() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame frame = new Http2Frame();
        frame.payloadActualLength = 0;
        frame.setFlags(1); // END_STREAM
        f.stream.onDataFrame(frame, f.ctx);
        // bodyData.length == 0 → L267 false branch
    }

    // ==================== getContentLength streaming, no declared content length ====================

    @Test void testGetContentLengthStreamingBodyStreamEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = -1;
        f.stream.handleRequest(false); // creates bodyStream
        f.stream.bodyStream.endStream();
        assertEquals(0, f.stream.getContentLength()); // bodyStream.totalLength = 0
    }

    // ==================== feedDataFrame with bodyStream ====================

    @Test void testFeedDataFrameWithBodyStream() throws Exception {
        Fixture f = createFixture();
        f.stream.handleRequest(false); // creates bodyStream
        Http2Frame frame = new Http2Frame();
        frame.frameData = "hello".getBytes();
        frame.payloadActualLength = 5;
        f.stream.feedDataFrame(frame);
        assertFalse(f.stream.endStream);
    }

    @Test void testFeedDataFrameEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.handleRequest(false); // creates bodyStream
        Http2Frame frame = new Http2Frame();
        frame.frameData = "hello".getBytes();
        frame.payloadActualLength = 5;
        frame.setFlags(1); // END_STREAM
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    // ==================== HPACK: null header value (L559) ====================

    @Test void testEncodeHpackHeadersNullValue() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("x-null-val", null); // value is null → L559 else-if false → skip
        assertNotNull(createFixture().stream.encodeHpackHeaders(mockResp(200, h)));
    }

    // ==================== recvWindow == 0 with bodyData at capacity ====================

}
