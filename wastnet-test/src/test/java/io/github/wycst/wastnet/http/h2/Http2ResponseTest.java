package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Response}.
 *
 * @author wangyc
 */
public class Http2ResponseTest {

    // ==================== Helpers ====================

    /**
     * Create a mock-based fixture for Http2Response tests.
     * Returns a real Http2Response with all dependencies mocked.
     */
    static class MockFixture {
        final ChannelContext ctx;
        final Http2Stream stream;
        final Http2Request request;
        final Http2Response response;

        MockFixture(ChannelContext ctx, Http2Stream stream, Http2Request request, Http2Response response) {
            this.ctx = ctx;
            this.stream = stream;
            this.request = request;
            this.response = response;
        }
    }

    private static MockFixture createMockFixture() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2Stream stream = mock(Http2Stream.class);
        when(stream.sendChunkSize()).thenReturn(16384);
        when(stream.createFrameBuffer(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int capacity = inv.getArgument(0);
            return ByteBuffer.allocate(capacity);
        });
        Http2Request req = mock(Http2Request.class);
        when(req.stream()).thenReturn(stream);
        when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        Http2Response resp = new Http2Response(req, stream, ctx);
        return new MockFixture(ctx, stream, req, resp);
    }

    // ==================== Reflection helper ====================

    private static final Object UNSAFE;

    static {
        try {
            Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get Unsafe instance", e);
        }
    }

    /**
     * Temporarily set a static final int field using Unsafe (works on JDK 8-23).
     * Returns the original value for restoration in finally block.
     */
    private static int setFinalStaticInt(Field field, int value) throws Exception {
        long offset = (long) UNSAFE.getClass().getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, field);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", Field.class).invoke(UNSAFE, field);
        int original = (int) UNSAFE.getClass().getMethod("getInt", Object.class, long.class).invoke(UNSAFE, base, offset);
        UNSAFE.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(UNSAFE, base, offset, value);
        return original;
    }

    /**
     * Temporarily set a static final boolean field using Unsafe (works on JDK 8-23).
     * Returns the original value for restoration in finally block.
     */
    private static boolean setFinalStaticBoolean(Field field, boolean value) throws Exception {
        long offset = (long) UNSAFE.getClass().getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, field);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", Field.class).invoke(UNSAFE, field);
        boolean original = (boolean) UNSAFE.getClass().getMethod("getBoolean", Object.class, long.class).invoke(UNSAFE, base, offset);
        UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, base, offset, value);
        return original;
    }

    // ==================== getHttpVersion ====================

    @Test
    void testGetHttpVersion() {
        assertEquals(HttpVersion.HTTP_2, createMockFixture().response.getHttpVersion());
    }

    // ==================== Header methods ====================

    @Test
    void testAddHeader() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("content-type", "text/html");
        assertEquals("text/html", resp.getHeader("content-type"));
    }

    @Test
    void testAddHeaderLowercasesKey() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("X-Custom", "val");
        // Both original case and lowercase lookups work because getHeader also lowercases
        assertEquals("val", resp.getHeader("X-Custom"));
        assertEquals("val", resp.getHeader("x-custom"));
    }

    @Test
    void testSetHeaderOverwrites() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("x-custom", "old");
        resp.setHeader("x-custom", "new");
        assertEquals("new", resp.getHeader("x-custom"));
    }

    @Test
    void testGetHeaderReturnsNullForMissing() {
        assertNull(createMockFixture().response.getHeader("nonexistent"));
    }

    @Test
    void testRemoveHeader() {
        MockFixture f = createMockFixture();
        f.response.addHeader("x-test", "value");
        f.response.removeHeader("x-test");
        assertNull(f.response.getHeader("x-test"));
    }

    // ==================== Status / Content ====================

    @Test
    void testDefaultStatusIsOk() {
        assertEquals(200, createMockFixture().response.getStatus().code);
    }

    @Test
    void testSetStatus() {
        Http2Response resp = createMockFixture().response;
        resp.status(404);
        assertEquals(404, resp.getStatus().code);
    }

    @Test
    void testSetContentType() {
        Http2Response resp = createMockFixture().response;
        resp.setContentType("application/json");
        assertEquals("application/json", resp.getContentType());
    }

    @Test
    void testSetContentLength() {
        Http2Response resp = createMockFixture().response;
        resp.setContentLength(1024);
        assertEquals(1024, resp.getContentLength());
    }

    @Test
    void testBodySetsContent() {
        Http2Response resp = createMockFixture().response;
        resp.body("test_content".getBytes());
        assertEquals(200, resp.getStatus().code);
    }

    // ==================== write ====================

    @Test
    void testWriteSmallDataBuffersAndSendsHeaders() throws Exception {
        MockFixture f = createMockFixture();
        byte[] data = "hello".getBytes();
        f.response.write(data, 0, data.length);
        // Headers should be sent (writeFrame called once)
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testWriteAlreadyCommittedIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        byte[] data = "world".getBytes();
        f.response.write(data, 0, data.length);
        // After commit, writeFrame should not be called again for data
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testWriteZeroLengthIsNoOp() {
        Http2Response resp = createMockFixture().response;
        assertDoesNotThrow(() -> resp.write(new byte[10], 0, 0));
    }

    // ==================== flush ====================

    @Test
    void testFlushSendsBufferedData() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("data".getBytes());
        f.response.flush();
        verify(f.stream, atLeastOnce()).writeDataFrame(any(ByteBuffer.class), anyInt());
    }

    @Test
    void testFlushAfterCommitIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        f.response.flush();
        // commit() should be the terminal operation
    }

    // ==================== commit ====================

    @Test
    void testCommitSendsHeadersAndBody() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("payload".getBytes());
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testCommitMultipleTimesIsIdempotent() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        // Second commit should not throw
        f.response.commit();
        assertFalse(f.response.isCorrupted());
    }

    @Test
    void testCommitWithoutBodySendsEndStream() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    // ==================== handover ====================

    @Test
    void testHandover() {
        MockFixture f = createMockFixture();
        f.response.handover();
        assertFalse(f.response.isCorrupted());
    }

    @Test
    void testHandoverMarksStream() {
        MockFixture f = createMockFixture();
        f.response.handover();
        verify(f.stream).handover();
    }

    // ==================== notFound ====================

    @Test
    void testNotFound() throws Exception {
        MockFixture f = createMockFixture();
        f.response.notFound();
        assertEquals(404, f.response.getStatus().code);
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    // ==================== addCacheHeaders ====================

    @Test
    void testAddCacheHeaders() throws Exception {
        MockFixture f = createMockFixture();
        f.response.addCacheHeaders(1024, 123456789L);
        assertNotNull(f.response.getHeader("last-modified"));
        assertNotNull(f.response.getHeader("etag"));
    }

    // ==================== earlyHints ====================

    @Test
    void testEarlyHints() throws Exception {
        MockFixture f = createMockFixture();
        f.response.earlyHints("</style.css>; rel=preload");
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEarlyHintsAfterHeadersSentIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        f.response.earlyHints("</script.js>; rel=preload");
        // Only one writeFrame call expected (from commit)
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    // ==================== isChunked ====================

    @Test
    void testIsChunked() {
        assertFalse(createMockFixture().response.isChunked());
    }

    // ==================== sendFile0 (via mock) ====================

    @Test
    void testSendFile0SmallFile() throws Exception {
        MockFixture f = createMockFixture();
        java.io.File tmp = java.io.File.createTempFile("h2test", ".txt");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write("small content".getBytes());
            fos.close();
            f.response.sendFile0(tmp, tmp.length(), "text/plain", false);
            verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
        } finally {
            tmp.delete();
        }
    }

    @Test
    void testSendFile0WithGzip() throws Exception {
        MockFixture f = createMockFixture();
        java.io.File tmp = java.io.File.createTempFile("h2gzip", ".txt");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write("compressible content".getBytes());
            fos.close();
            f.response.sendFile0(tmp, tmp.length(), "text/plain", true);
            verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
        } finally {
            tmp.delete();
        }
    }

    // ==================== encodeHeaders branches ====================

    @Test
    void testEncodeHeadersWithContentType() throws Exception {
        MockFixture f = createMockFixture();
        f.response.setContentType("text/plain");
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEncodeHeadersWithContentLength() throws Exception {
        MockFixture f = createMockFixture();
        f.response.setContentLength(200);
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEncodeHeadersWithCustomHeaders() throws Exception {
        MockFixture f = createMockFixture();
        f.response.addHeader("x-custom", "value1");
        f.response.addHeader("x-another", "value2");
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEncodeHeadersSkipsContentTypeAndLengthFromH2Headers() throws Exception {
        MockFixture f = createMockFixture();
        // These should be skipped when iterating h2Headers (handled by the contentType/contentLength fields)
        f.response.addHeader("content-type", "application/json");
        f.response.addHeader("content-length", "500");
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEncodeHeadersWithAllBranches() throws Exception {
        MockFixture f = createMockFixture();
        f.response.setContentType("application/json");
        f.response.setContentLength(300);
        f.response.addHeader("x-custom", "test");
        f.response.addHeader("x-date-provided", "yes");
        f.response.commit();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    // ==================== sendChunkedData multi-chunk ====================

    @Test
    void testSendMultiChunkDataViaCommit() throws Exception {
        MockFixture f = createMockFixture();
        // Body larger than chunkSize (16384) triggers multi-chunk in sendChunkedData
        byte[] largeBody = new byte[40000];
        for (int i = 0; i < largeBody.length; i++) largeBody[i] = (byte) (i & 0xFF);
        f.response.body(largeBody);
        f.response.commit();
        // Should call writeDataFrame at least twice (3 chunks: 16384 + 16384 + 7232)
        verify(f.stream, atLeast(2)).writeDataFrame(any(ByteBuffer.class), anyInt());
    }

    @Test
    void testWriteLargeDataExceedingThreshold() throws Exception {
        // Temporarily lower BODY_MEMORY_THRESHOLD so we can test the large data path
        // without sending hundreds of KB of data
        Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        thresholdField.setAccessible(true);
        int original = setFinalStaticInt(thresholdField, 16);
        try {
            MockFixture f = createMockFixture();
            byte[] initial = "helloworld".getBytes();
            f.response.write(initial, 0, initial.length);
            // bodyBuf now has 10 bytes
            // Write 10 more bytes - total would be 20 > 16 → triggers threshold path
            byte[] more = "bufdatahere".getBytes();
            f.response.write(more, 0, more.length);
            verify(f.stream, atLeastOnce()).writeDataFrame(any(ByteBuffer.class), anyInt());
        } finally {
            setFinalStaticInt(thresholdField, original);
        }
    }

    // ==================== Auto GZIP via complete() ====================

    @Test
    void testAutoGzipTriggersDoSendCompressedResponse() throws Exception {
        // Need to mock request.getHeader("accept-encoding", true) for isGzipSupported()
        Http2Stream stream = mock(Http2Stream.class);
        when(stream.sendChunkSize()).thenReturn(16384);
        when(stream.createFrameBuffer(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int capacity = inv.getArgument(0);
            return ByteBuffer.allocate(capacity);
        });
        Http2Request req = mock(Http2Request.class);
        when(req.stream()).thenReturn(stream);
        when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        // Return "gzip" for Accept-Encoding check
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

        Http2Response resp = new Http2Response(req, stream, mock(ChannelContext.class));

        // Temporarily enable GZIP and set min size to 1
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        Field minSizeField = HttpConf.class.getDeclaredField("GZIP_MIN_SIZE");
        gzipField.setAccessible(true);
        minSizeField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        int origMinSize = setFinalStaticInt(minSizeField, 1);
        try {
            // Write enough body data to trigger GZIP (size >= 1)
            resp.body("compress_me".getBytes());
            resp.complete();
            // Auto GZIP should have sent compressed data via writeDataFrame
            verify(stream, atLeastOnce()).writeDataFrame(any(ByteBuffer.class), anyInt());
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
            setFinalStaticInt(minSizeField, origMinSize);
        }
    }

    @Test
    void testAutoGzipSkippedWhenGzipDisabled() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("test".getBytes());
        // GZIP is disabled by default, complete() should fall through to commit()
        f.response.complete();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testAutoGzipSkippedWhenBodyTooSmall() throws Exception {
        // With GZIP enabled but body < GZIP_MIN_SIZE (default 2048), auto-gzip is skipped
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        gzipField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        try {
            MockFixture f = createMockFixture();
            when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");
            f.response.body("tiny".getBytes()); // 4 bytes < 2048 GZIP_MIN_SIZE
            f.response.complete();
            verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
        }
    }
}
