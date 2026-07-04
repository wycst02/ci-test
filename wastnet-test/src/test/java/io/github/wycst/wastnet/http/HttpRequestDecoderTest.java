package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpRequestDecoderTest {

    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static Object origPreserve, origPipeline, origTimeout;

    @BeforeAll
    public static void setupConfig() throws Exception {
        if (initialized.getAndSet(true)) return;
        origPreserve = setFinalStatic(HttpConf.class, "PRESERVE_HEADER_ORDER", true);
        origPipeline = setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", true);
        origTimeout = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 50L);
    }

    @AfterAll
    public static void restoreConfig() throws Exception {
        if (origPreserve != null) {
            setFinalStatic(HttpConf.class, "PRESERVE_HEADER_ORDER", origPreserve);
            setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", origPipeline);
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", origTimeout);
        }
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static <T> Object setFinalStatic(Class<?> clazz, String fieldName, T value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        sun.misc.Unsafe unsafe = getUnsafe();
        Class<?> type = field.getType();
        long offset = unsafe.staticFieldOffset(field);
        Object base = unsafe.staticFieldBase(field);
        if (type == long.class) {
            unsafe.putLong(base, offset, ((Number) value).longValue());
        } else if (type == int.class) {
            unsafe.putInt(base, offset, ((Number) value).intValue());
        } else if (type == boolean.class) {
            unsafe.putBoolean(base, offset, (Boolean) value);
        } else {
            unsafe.putObject(base, offset, value);
        }
        return original;
    }

    private final AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();

    private ChannelContext createCtx() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        return ctx;
    }

    private void decodeAll(HttpRequestDecoder d, String data, ChannelContext ctx) throws Exception {
        byte[] b = data.getBytes();
        d.decode(b, 0, b.length, ctx);
    }

    // ===== Valid requests through handler =====

    @Test
    public void testValidGet() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testPostWithBody() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "POST /x HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc", createCtx());
        assertEquals("POST", captured.get().getMethod().name());
    }

    @Test
    public void testContentType() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Type: application/json\r\n\r\n", createCtx());
        assertEquals("application/json", captured.get().getContentType());
    }

    @Test
    public void testContentLength() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello", createCtx());
        assertEquals(5, captured.get().getContentLength());
    }

    @Test
    public void testByteByByte() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        byte[] data = "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        for (byte aData : data) {
            d.decode(new byte[]{aData}, 0, 1, ctx);
        }
        assertNotNull(captured.get());
    }

    @Test
    public void testSplitDecode() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
        decodeAll(d, "Host: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testZeroLen() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        d.decode(new byte[0], 0, 0, createCtx());
    }

    @Test
    public void testShallow() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        d.setShallow(true);
        decodeAll(d, "GET /t HTTP/1.1\r\nHost: a\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testBoundaryCRatEdge() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(), 0, 31, ctx);
        d.decode("\n".getBytes(), 0, 1, ctx);
        // Coverage: expectLF path in readBoundary
    }

    @Test
    public void testBoundaryLoneLF() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testBodySplit() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("POST / HTTP/1.1\r\nContent-Length: 6\r\n\r\n".getBytes(), 0, 38, ctx);
        decodeAll(d, "hello!", ctx);
    }

    @Test
    public void testBadBoundaryEndsWithSpace() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        d.decode("GET / HTTP/1.1\r\nHost: a\r\n ".getBytes(), 0, 25, createCtx());
    }

    @Test
    public void testTimeoutRecovery() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
        Thread.sleep(150);
        decodeAll(d, "Host: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testHeaderValueSplit() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("GET / HTTP/1.1\r\nX-V: ".getBytes(), 0, 20, ctx);
        decodeAll(d, "split\r\n\r\n", ctx);
        assertEquals("split", captured.get().getHeader("x-v"));
    }

    @Test
    public void testShortHeader() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nK: v\r\n\r\n", createCtx());
        assertEquals("v", captured.get().getHeader("k"));
    }

    // ===== Content-Length > BODY_MAX_SIZE = 10 =====

    @Test
    public void testContentTooLarge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "BODY_MAX_SIZE", 10L);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            ChannelContext ctx = createCtx();
            // Content-Length: 20 > BODY_MAX_SIZE=10 → REQUEST_ENTITY_TOO_LARGE
            decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: 20\r\n\r\n", ctx);
        } finally {
            setFinalStatic(HttpConf.class, "BODY_MAX_SIZE", orig);
        }
    }

    @Test
    public void testContentLengthNormal() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello", createCtx());
        assertNotNull(captured.get());
        assertEquals(5, captured.get().getContentLength());
    }

    @Test
    public void testDuplicateHeader() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Two same headers → merge into list
        decodeAll(d, "GET / HTTP/1.1\r\nX-Dup: first\r\nX-Dup: second\r\n\r\n", ctx);
        // Coverage: prev != null → add to list in addHeader
    }

    @Test
    public void testInvalidContentLength() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Non-numeric content-length → NumberFormatException → BAD_REQUEST
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: abc\r\n\r\n", ctx);
        // Coverage: NumberFormatException catch in addHeader
    }

    @Test
    public void testMaxSingleHeaderSize() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_SINGLE_HEADER_SIZE", 5);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            decodeAll(d, "GET / HTTP/1.1\r\nX: toolong\r\n\r\n", createCtx());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_SINGLE_HEADER_SIZE", orig);
        }
    }

    // ===== readStartLine negative byte branch =====

    @Test
    public void testStartLineNegativeBytes() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Build request with negative byte (0x80) in URI
        byte[] prefix = "GET /\u00ff".getBytes("ISO-8859-1");
        byte[] suffix = " HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        byte[] req = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(suffix, 0, req, prefix.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: negative byte in readStartLine SWAR path
    }

    // ===== readHeaderValue negative bytes =====

    @Test
    public void testHeaderValueNegativeBytes() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Header value with negative byte (0x80)
        byte[] prefix = "GET / HTTP/1.1\r\nX-Neg: v".getBytes();
        byte[] mid = new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0x82};
        byte[] suffix = "al\r\n\r\n".getBytes();
        byte[] req = new byte[prefix.length + mid.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(mid, 0, req, prefix.length, mid.length);
        System.arraycopy(suffix, 0, req, prefix.length + mid.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: negative bytes in readHeaderValue
    }

    // ===== readBoundary error paths (byte-by-byte) =====

    @Test
    public void testBoundaryExpectLFThenCR() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // End headers with CR at buffer boundary → expectLF=true
        d.decode("GET / HTTP/1.1\r\nHost: a\r\n".getBytes(), 0, 24, ctx);
        // Then send \r instead of \n → BAD_REQUEST
        d.decode("\r".getBytes(), 0, 1, ctx);
        // Coverage: expectLF true but next byte is not \n
    }

    @Test
    public void testBoundaryOffsetEqualsLimit() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Byte-by-byte feed ending at boundary \r → expectLF=true, readState=ReadBoundary
        byte[] data = "GET / HTTP/1.1\r\nHost: a\r\n\r".getBytes();
        for (int i = 0; i < data.length; i++) {
            d.decode(new byte[]{data[i]}, 0, 1, ctx);
        }
        // Now readState=ReadBoundary, empty 3-arg enters readBoundary with offset==limit
        d.decode(new byte[0], 0, 0);
    }

    @Test
    public void testBoundaryExpectLFThenCR_byByte() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        byte[] data = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        // Feed byte by byte, but replace final \n with \r to trigger error
        for (int i = 0; i < data.length - 1; i++) {
            d.decode(new byte[]{data[i]}, 0, 1, ctx);
        }
        // Last expected byte is \n, but instead feed \r
        d.decode(new byte[]{'\r'}, 0, 1, ctx);
        // Coverage: boundary error with byte-by-byte feeding
    }

    // ===== readBody content-length > MAX_BODY_IN_MEMORY → stream mode =====

    // ===== Pipeline / mergeRemainingBytes =====

    @Test
    public void testPipelineRemainingBytes() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Two requests in one buffer: first completes, remaining = second request
        decodeAll(d, "GET /1 HTTP/1.1\r\nHost: a\r\n\r\nGET /3 HTTP/1.1\r\nHost: c\r\n\r\n", ctx);
        assertNotNull(captured.get());
        // Second decode call triggers mergeRemainingBytes
        captured.set(null);
        decodeAll(d, "GET /x HTTP/1.1\r\nHost: b\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    // ===== addHeader branch coverage =====

    @Test
    public void testContentEncodingHeader() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        // "Content-Encoding" starts with "Content-" but is neither Content-Type nor Content-Length
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Encoding: gzip\r\n\r\n", createCtx());
        assertEquals("gzip", captured.get().getHeader("content-encoding"));
    }

    @Test
    public void testExpectContinueHeader() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        // Expect: 100-continue triggers expectContinue=true
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testBodyStreamMode() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", 4);
        try {
            captured.set(null);
            HttpRequestDecoder d = new HttpRequestDecoder();
            ChannelContext ctx = createCtx();
            // Content-Length: 10 > MAX_BODY_IN_MEMORY(4) → BODY_MODE_STREAM
            // onDecoded creates HttpStreamRequest
            decodeAll(d, "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\n0123456789", ctx);
            assertNotNull(captured.get());
            assertTrue(captured.get().isStream());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", orig);
        }
    }

    // ===== Remaining decoder branch coverage =====

    @Test
    public void testZeroHeaders() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testLoneLFAtStartLine() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        d.decode("\n".getBytes(), 0, 1, ctx);
        // Coverage: L222-L225
    }

    @Test
    public void testStartLineLoneLFThenLF() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Start line ends with \n (no \r), next data is \n → readHeaders entry b=='\n' (L275)
        d.decode("GET / HTTP/1.1\n\n".getBytes(), 0, 16, ctx);
        // Coverage: L275 b == '\n' at readHeaders entry
    }

    @Test
    public void testGetResultBadPath() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET / HTTP/1.1\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        assertTrue(msg instanceof HttpBadRequest);
    }

    @Test
    public void testGetResultGoodPath() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        assertFalse(((HttpRequest) msg).isBad());
    }

    @Test
    public void testSwitchDefault() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET /x HTTP/1.1\nHost: a\n\n".getBytes();
        d.decode(b, 0, b.length);
        // Now readState == Completed, second 3-arg decode hits default case
        d.decode(new byte[0], 0, 0);
    }

    @Test
    public void testRequestTimeout() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 10L);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            ChannelContext ctx = createCtx();
            d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
            Thread.sleep(50);
            d.decode("Host".getBytes(), 0, 4, ctx);
        } finally {
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", orig);
        }
    }

    @Test
    public void testExpectNotContinue() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-xxx\r\n\r\n", createCtx());
    }

    @Test
    public void testTripleDuplicateHeader() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nX-Dup: v1\r\nX-Dup: v2\r\nX-Dup: v3\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testChunkedTransferEncoding() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testUriTooLong() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 3);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            decodeAll(d, "GET /abc HTTP/1.1\r\nHost: a\r\n\r\n", createCtx());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }

    @Test
    public void testHeaderKeyTooLarge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", 10);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            byte[] data = "GET / HTTP/1.1\r\nABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();
            d.decode(data, 0, data.length, createCtx());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", orig);
        }
    }

    @Test
    public void testHeaderValueTooLarge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", 10);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            byte[] data = "GET / HTTP/1.1\r\nK:ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();
            d.decode(data, 0, data.length, createCtx());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", orig);
        }
    }

    @Test
    public void testHeaderValueEndsWithLoneLF() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        decodeAll(d, "GET / HTTP/1.1\r\nK: v\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    // ===== expectContinue true branch (needs connected channel so writeFlush succeeds) =====

    @Test
    public void testExpectContinueBranchCoverage() throws Exception {
        captured.set(null);
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(0));
        int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("localhost", port));
        ch.configureBlocking(false);
        SocketChannel accepted = ssc.accept();
        accepted.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", ctx);
            // Coverage: L314 expectContinue && status == null && ctx != null → true
        } finally {
            accepted.close();
            ch.close();
            ssc.close();
        }
    }

    // ===== readStartLine remaining branches =====

    @Test
    public void testUriTooLongAtBufferEdge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 1);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            // Buffer ends at whitespace after token, token exceeds MAX_URI_LENGTH
            d.decode("GE".getBytes(), 0, 2, createCtx());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }

    @Test
    public void testWhitespaceWithCRInStartLine() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        // Extra space after version before \r\n → while loop hits \r in whitespace (L247)
        decodeAll(d, "GET /x HTTP/1.1 \r\nHost: a\r\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    @Test
    public void testWhitespaceWithLFViaTab() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        // Tab before \n in version separator → L247 b == '\n' in whitespace
        decodeAll(d, "GET /x HTTP/1.1\t\n\r\n", createCtx());
        assertNotNull(captured.get());
    }

    // ===== readHeaderKey quick colon mismatch (L334) via negative byte =====

    @Test
    public void testHeaderKeyNegativeBytes() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Header key with negative byte (0xBA) that may trigger maskOfColon false match
        // Header key starting with 0xBB triggers maskOfColon false positive; add padding for SWAR entry
        byte[] prefix = "GET / HTTP/1.1\r\n".getBytes();
        byte[] mid = new byte[]{(byte) 0xBB};
        byte[] suffix = ": val\r\n\r\nLorem ipsum dolor sit amet".getBytes();
        byte[] req = new byte[prefix.length + mid.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(mid, 0, req, prefix.length, mid.length);
        System.arraycopy(suffix, 0, req, prefix.length + mid.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: L334 buf[tarOff] == ':' check → false → break quick
    }

    // ===== onDecoded version/method null branches =====

    @Test
    public void testUnsupportedVersion() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // HTTP/9.9 is not recognized by HttpVersion.of() → version == null → HTTP_VERSION_NOT_SUPPORTED
        decodeAll(d, "GET / HTTP/9.9\r\nHost: localhost\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    @Test
    public void testUnrecognizedMethod() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // "INVALID" is not recognized by HttpMethod.fromString() → method == null → NOT_IMPLEMENTED
        decodeAll(d, "INVALID /path HTTP/1.1\r\nHost: localhost\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== readBody multi-chunk accumulation (L439-440 bodySize + len < contentLength) =====

    @Test
    public void testBodyMultiChunkAccumulation() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // First: headers + incomplete body "abc" (3 of 10 bytes)
        byte[] chunk1 = "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".getBytes("ISO-8859-1");
        d.decode(chunk1, 0, chunk1.length, ctx);
        // Second: "defg" (4 more bytes, total 7, still < 10) → hits L439-440
        d.decode("defg".getBytes("ISO-8859-1"), 0, 4, ctx);
        // Third: complete "hij" → bodySize+len >= contentLength → completedBody → onDecoded
        decodeAll(d, "hij", ctx);
        assertNotNull(captured.get());
        assertEquals("abcdefghij", new String(captured.get().getBodyData(), "ISO-8859-1"));
    }

    // ===== readBody LENGTH_REQUIRED (L430) with PIPELINE_ENABLED = false =====

    @Test
    public void testLengthRequired() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", false);
        try {
            captured.set(null);
            HttpRequestDecoder d = new HttpRequestDecoder();
            ChannelContext ctx = createCtx();
            // POST without Content-Length, PIPELINE_DISABLED → len > 0 && !PIPELINE_ENABLED && !hasContentLength
            decodeAll(d, "POST / HTTP/1.1\r\nHost: a\r\n\r\nx", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
            assertTrue(req.isBad());
        } finally {
            setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", orig);
        }
    }

    // ===== readHeaderKey SWAR loop exhaust (L342 offset += 24) =====

    @Test
    public void testHeaderKeySWARExhaust() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Header key > 24 bytes without colon, SWAR processes 3×8 = 24 bytes, finds nothing → L342
        StringBuilder sb = new StringBuilder();
        sb.append("GET / HTTP/1.1\r\n");
        for (int i = 0; i < 25; i++) sb.append('A');
        sb.append(": val\r\n\r\n");
        decodeAll(d, sb.toString(), ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertFalse(req.isBad());
    }

    // ===== readHeaderKey split across buffer boundary (SWAR skipped, httpBuf accumulation) =====

    @Test
    public void testHeaderKeySplit() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Header key "X-Cus" ends at buffer boundary → httpBuf accumulates
        d.decode("GET / HTTP/1.1\r\nX-Cus".getBytes(), 0, 21, ctx);
        decodeAll(d, "tom: value\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertEquals("value", req.getHeader("x-custom"));
    }

    // ===== prepareRequestContent IOException branch (L317 status = INTERNAL_SERVER_ERROR) =====

    @Test
    public void testPrepareRequestContentIOException() throws Exception {
        captured.set(null);
        // Unconnected channel: writeFlushWithoutThrow silently catches NotYetConnectedException
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            // Expect: 100-continue → prepareRequestContent → writeFlushWithoutThrow catches exception
            decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
        } finally {
            ch.close();
        }
    }

    // ===== L313 branch: expectContinue=true AND status!=null (skip if body) =====

    @Test
    public void testExpectContinueWithExistingStatus() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Expect: 100-continue sets expectContinue=true, then invalid Content-Length sets BAD_REQUEST
        // prepareRequestContent: expectContinue=true, status!=null → skip write
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: abc\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== L313 branch: expectContinue=true AND ctx==null (3-arg decode) =====

    @Test
    public void testExpectContinueWithoutCtx() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        // 3-arg decode: this.ctx stays null → prepareRequestContent skips write (ctx==null)
        byte[] b = "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        // Should get a valid request (no error) via getResult
        assertFalse(((HttpRequest) msg).isBad());
    }

    // ===== L485 branch: onBadDecoded with bodyMode=STREAM (.stream(true)) =====

    @Test
    public void testOnBadDecodedWithStreamMode() throws Exception {
        Object origMem = setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", 4);
        try {
            captured.set(null);
            HttpRequestDecoder d = new HttpRequestDecoder();
            ChannelContext ctx = createCtx();
            // Content-Length: 10 > MAX_BODY_IN_MEMORY(4) → STREAM mode
            // Expect: 100-xxx sets EXPECTATION_FAILED during addHeader
            // readBody sets bodyMode=STREAM, then handleBadOrTimeout catches status
            decodeAll(d, "POST / HTTP/1.1\r\nContent-Length: 10\r\nExpect: 100-xxx\r\n\r\n0123456789", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
            assertTrue(req.isBad());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", origMem);
        }
    }

    // ===== L485 branch: onBadDecoded with bodyMode=CHUNKED (.chunked(true)) =====

    @Test
    public void testOnBadDecodedWithChunkedMode() throws Exception {
        captured.set(null);
        HttpRequestDecoder d = new HttpRequestDecoder();
        ChannelContext ctx = createCtx();
        // Transfer-Encoding: chunked → readBody sets bodyMode=CHUNKED
        // Expect: 100-xxx sets EXPECTATION_FAILED during addHeader
        // readBody sets bodyMode=CHUNKED, then handleBadOrTimeout catches status → onBadDecoded with chunked(true)
        decodeAll(d, "GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nExpect: 100-xxx\r\n\r\n5\r\nhello\r\n0\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== L142/L145 branch: getResult with non-null status =====

    @Test
    public void testGetResultWithStatus() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 3);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            // URI too long sets status to REQUEST_URI_TOO_LONG, then getResult()
            byte[] b = "GET /abc HTTP/1.1\r\nHost: a\r\n\r\n".getBytes();
            d.decode(b, 0, b.length);
            HttpMessage msg = d.getResult();
            assertTrue(msg instanceof HttpBadRequest);
            assertTrue(((HttpRequest) msg).isBad());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }
}
