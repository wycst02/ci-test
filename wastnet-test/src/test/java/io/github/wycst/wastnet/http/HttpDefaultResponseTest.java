package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpDefaultResponse}.
 * <p>
 * Uses a minimal subclass to test concrete methods without needing
 * full ChannelContext integration.
 */
public class HttpDefaultResponseTest {

    @Test
    public void testIsChunkedDefaultFalse() {
        TestDefaultResp resp = createResponse();
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testSetChunkedTrue() {
        TestDefaultResp resp = createResponse();
        resp.setChunked(true);
        Assertions.assertTrue(resp.isChunked());
    }

    @Test
    public void testSetChunkedFalse() {
        TestDefaultResp resp = createResponse();
        resp.setChunked(true);
        resp.setChunked(false);
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testSetChunkedEncoding() {
        TestDefaultResp resp = createResponse();
        resp.setChunkedEncoding();
        Assertions.assertTrue(resp.isChunked());
    }

    @Test
    public void testRemoveChunkedEncoding() {
        TestDefaultResp resp = createResponse();
        resp.setChunkedEncoding();
        resp.removeChunkedEncoding();
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testSetChunkedAfterExplicitContentLengthThrows() {
        TestDefaultResp resp = createResponse();
        // Set Content-Length header explicitly to trigger hasExplicitContentLength flag
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100);
        Assertions.assertThrows(IllegalStateException.class, () -> resp.setChunked(true));
    }

    @Test
    public void testIsCorruptedInitiallyFalse() {
        TestDefaultResp resp = createResponse();
        Assertions.assertFalse(resp.isCorrupted());
    }

    @Test
    public void testSetKeepAliveDoesNotThrow() {
        TestDefaultResp resp = createResponse();
        Assertions.assertDoesNotThrow(() -> resp.setKeepAlive(true));
        Assertions.assertDoesNotThrow(() -> resp.setKeepAlive(false));
    }

    @Test
    public void testSetLastModifiedDoesNotThrow() {
        TestDefaultResp resp = createResponse();
        Assertions.assertDoesNotThrow(() -> resp.setLastModified(System.currentTimeMillis()));
    }

    @Test
    public void testWriteChunkedWithoutChunkedThrows() {
        TestDefaultResp resp = createResponse();
        Assertions.assertThrows(IllegalStateException.class, () -> resp.writeChunked("data".getBytes()));
    }

    @Test
    public void testWriteChunkedWithChunkedDoesNotThrow() throws IOException {
        TestDefaultResp resp = createResponse();
        resp.setChunked(true);
        resp.writeChunked("data".getBytes());
        // no exception = success
    }

    @Test
    public void testAddCacheHeadersDoesNotThrow() {
        TestDefaultResp resp = createResponse();
        Assertions.assertDoesNotThrow(() -> resp.callAddCacheHeaders(100, 123456789L));
    }

    @Test
    public void testResetDoesNotThrow() {
        TestDefaultResp resp = createResponse();
        Assertions.assertDoesNotThrow(() -> resp.reset());
    }

    // ==================== Helpers ====================

    private TestDefaultResp createResponse() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        // return an empty connection header to allow chunked encoding test
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        ChannelContext mockCtx = mock(ChannelContext.class);
        return new TestDefaultResp(mockReq, mockCtx);
    }

    /**
     * Minimal subclass of HttpDefaultResponse that overrides nothing
     * from the default implementations.
     */
    static class TestDefaultResp extends HttpDefaultResponse {

        TestDefaultResp(HttpRequest request, ChannelContext ctx) {
            super(request, ctx);
        }

        // Expose protected methods for testing
        public void callAddCacheHeaders(long fileSize, long lastModified) throws IOException {
            addCacheHeaders(fileSize, lastModified);
        }

        @Override
        public boolean isCorrupted() {
            return super.isCorrupted();
        }

        @Override
        public void reset() {
            super.reset();
        }
    }
}
