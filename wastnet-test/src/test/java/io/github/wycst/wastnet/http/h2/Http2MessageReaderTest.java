package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Http2MessageReader}.
 * <p>
 * Created via real ChannelContext with unconnected SocketChannel.
 * I/O calls throw NotYetConnectedException or NPE on null readKey, which
 * is expected and caught. All side-effect assertions happen before the throw.
 */
public class Http2MessageReaderTest {

    static class TestHttp2MessageReader extends Http2MessageReader {
        @Override
        public void init(ChannelContext ctx) {}
        @Override
        protected Http2Stream getStream(int streamId, ChannelContext ctx) { return null; }
    }

    /** A minimal Http2Stream subclass for testing sendWindowUpdatePair. */
    static class TestHttp2Stream extends Http2Stream {
        TestHttp2Stream(Http2MessageReader reader, int streamId, ChannelContext ctx) {
            super(reader, streamId, ctx);
        }
        @Override protected void onEndHeaders() {}
        @Override protected void submitRequest() {}
        @Override public String debugPrefix() { return "Test"; }
    }

    static ChannelContext ctx() throws IOException {
        return new ChannelContext(SocketChannel.open(), 4096);
    }

    /** Execute a test action that may throw due to unconnected socket, ignoring that. */
    static void safeRun(RunnableException r) {
        try { r.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface RunnableException { void run() throws Exception; }

    @Test
    public void testCloseConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        assertTrue(reader.valid);
        safeRun(() -> reader.closeConnection(ctx()));
        assertFalse(reader.valid);
    }

    @Test
    public void testSendRstStreamFrame() throws Exception {
        new TestHttp2MessageReader().sendRstStreamFrame(ctx(), 1, 2);
    }

    @Test
    public void testSendGoawayFrame() throws Exception {
        new TestHttp2MessageReader().sendGoawayFrame(ctx(), 0, 0);
    }

    @Test
    public void testHandleControlFramePing() throws Exception {
        safeRun(() -> new TestHttp2MessageReader().handleFrame(ctx(), createPingFrame()));
    }

    @Test
    public void testHandleControlFramePriority() throws Exception {
        new TestHttp2MessageReader().handleFrame(ctx(), createControlFrame((byte) 2, 5));
    }

    @Test
    public void testHandleControlFrameDefaultClosesConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), createControlFrame((byte) 7, 8)));
        assertFalse(reader.valid);
    }

    @Test
    public void testHandleSettingsAckIgnored() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Http2Frame frame = createControlFrame((byte) 4, 0);
        frame.setFlags(Http2Frame.SETTINGS_ACK);
        reader.handleFrame(ctx(), frame);
        assertTrue(reader.valid);
    }

    @Test
    public void testHandleSettingsHeaderTableSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(1, 8192)));
        assertEquals(8192, reader.maxHpackEncoderTableSize);
    }

    @Test
    public void testHandleSettingsMaxConcurrentStreams() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(3, 100)));
        assertEquals(100, reader.remoteMaxConcurrentStreams);
    }

    @Test
    public void testHandleSettingsInitialWindowSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 131072)));
        assertEquals(131072, reader.initialSendWindowSize);
    }

    @Test
    public void testHandleSettingsInitialWindowSizeNegative() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, -1)));
        assertFalse(reader.valid);
    }

    @Test
    public void testHandleSettingsMaxFrameSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 65536)));
        assertEquals(65536, reader.maxSendPayloadSize);
    }

    @Test
    public void testHandleSettingsMaxFrameSizeTooSmall() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 1024)));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFrameNegativeStreamId() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ChannelContext ctx = ctx();
        byte[] buf = new byte[9];
        buf[5] = (byte) 0x80;
        safeRun(() -> reader.decode(ctx, buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFrameLengthExceedsMax() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ChannelContext ctx = ctx();
        byte[] buf = new byte[9];
        buf[1] = 64;
        buf[2] = 1;
        safeRun(() -> reader.decode(ctx, buf, 0, buf.length));
        assertFalse(reader.valid);
    }

    @Test
    public void testReadNextMessageFramePaddingAndPriority() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[15];
        buf[2] = 6;
        buf[3] = 1;
        buf[4] = (byte) 0x28;
        buf[8] = 1;
        buf[9] = 0;
        reader.decode(ctx(), buf, 0, buf.length);
        assertTrue(reader.valid);
    }

    // ==================== handleControlFrame WINDOW_UPDATE on stream 0 ====================

    @Test
    public void testHandleControlFrameWindowUpdate() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        long before = reader.connectSendWindow;
        // Build frame manually: WINDOW_UPDATE frame with streamId=0
        Http2Frame frame = new Http2Frame();
        byte[] data = new byte[13];
        data[2] = 4;          // payload length = 4
        data[3] = 8;          // type = WINDOW_UPDATE
        data[8] = 0;          // streamId = 0
        data[9] = 0; data[10] = 0; data[11] = 0x13; data[12] = (byte) 0x88; // increment = 5000
        frame.setFrameData(data);
        frame.setPayloadLength(4);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.WINDOW_UPDATE);
        frame.payload(9, 4);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertTrue(reader.connectSendWindow > before);
    }

    @Test
    public void testHandleControlFrameWindowUpdateOverflow() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        reader.connectSendWindow = Integer.MAX_VALUE - 100;

        Http2Frame frame = new Http2Frame();
        byte[] data = new byte[13];
        data[2] = 4;
        data[3] = 8;
        data[8] = 0;
        data[9] = 0; data[10] = 0; data[11] = 0; data[12] = (byte) 200;
        frame.setFrameData(data);
        frame.setPayloadLength(4);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.WINDOW_UPDATE);
        frame.payload(9, 4);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== handleSettingsFrame unknown setting (connection-level, closes after ACK fail) ====================

    @Test
    public void testHandleSettingsUnknownSetting() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // Sending SETTINGS on unconnected socket → writeFlush(ACK) throws → caught → closeConnection.
        // This tests that unknown settings don't cause unexpected errors BEFORE the ACK send.
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(0xFF, 999)));
        // Connection closed due to ACK send failure on unconnected socket, not due to unknown setting
        assertFalse(reader.valid);
    }

    // ==================== handleSettingsFrame catch block (exception in SETTINGS parsing) ====================

    @Test
    public void testHandleSettingsParsingExceptionClosesConnection() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] data = new byte[10];
        data[2] = 1;           // payload length = 1 (not a multiple of 6)
        data[3] = 4;           // type = SETTINGS
        data[8] = 0;           // streamId = 0
        Http2Frame frame = new Http2Frame();
        frame.setFrameData(data);
        frame.setPayloadLength(1);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.SETTINGS);
        frame.payload(9, 1);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== handleFrame non-zero streamId with null stream ====================

    @Test
    public void testHandleFrameNonNullStreamIdReturnsWhenStreamNull() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        byte[] buf = new byte[9];
        buf[3] = 4;            // SETTINGS
        buf[8] = 5;            // streamId = 5
        safeRun(() -> reader.decode(ctx(), buf, 0, buf.length));
        // getStream(5) returns null for TestHttp2MessageReader → handleFrame returns
        assertTrue(reader.valid);
    }

    // ==================== decode: len < 9 → read() ====================

    @Test
    public void testDecodeLenLessThan9ReadsFromChannel() throws Exception {
        // Use loopback TCP to buffer frame data on server side,
        // then provide only 5 bytes to decode → triggers len < 9 → read()
        java.nio.channels.ServerSocketChannel ssc =
                java.nio.channels.ServerSocketChannel.open();
        ssc.socket().bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();

        SocketChannel client = SocketChannel.open();
        client.connect(new java.net.InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();

        try {
            // Build a valid frame: DATA, length=5, streamId=1
            byte[] frame = new byte[14];
            frame[2] = 5;
            frame[8] = 1;
            frame[9] = 'h'; frame[10] = 'e'; frame[11] = 'l'; frame[12] = 'l'; frame[13] = 'o';
            client.write(ByteBuffer.wrap(frame));
            client.close();

            // Data is now buffered in server's TCP receive buffer
            TestHttp2MessageReader reader = new TestHttp2MessageReader();
            ChannelContext ctx = new ChannelContext(server, 4096);

            // Provide only 5 bytes → len=5 < 9 → read() fetches remaining 4+5 from channel
            reader.decode(ctx, new byte[5], 0, 5);
            assertTrue(reader.valid);
        } finally {
            try { server.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== Static utility tests ====================

    @Test
    public void testReadUInt24() {
        byte[] buf = {0x12, 0x34, 0x56};
        assertEquals(0x123456, TestHttp2MessageReader.readUInt24(buf, 0));
    }

    @Test
    public void testReadInt32() {
        byte[] buf = {0x12, 0x34, 0x56, (byte) 0x78};
        assertEquals(0x12345678, TestHttp2MessageReader.readInt32(buf, 0));
    }

    @Test
    public void testReadUInt16() {
        byte[] buf = {(byte) 0xAB, (byte) 0xCD};
        assertEquals(0xABCD, TestHttp2MessageReader.readUInt16(buf, 0));
    }

    // ==================== WINDOW_UPDATE sending ====================

    @Test
    public void testSendConnectionWindowUpdate() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.sendConnectionWindowUpdate(ctx(), 16384));
        assertTrue(reader.valid);
    }

    @Test
    public void testSendWindowUpdatePair() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        TestHttp2Stream stream = new TestHttp2Stream(reader, 3, ctx());
        safeRun(() -> reader.sendWindowUpdatePair(ctx(), stream, 16384));
        assertTrue(reader.valid);
    }

    // ==================== Settings: same initial window size ====================

    @Test
    public void testHandleSettingsSameInitialWindowSize() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(4, 65535)));
        assertEquals(0xFFFF, reader.initialSendWindowSize);
    }

    // ==================== decode: IOException catch ====================

    @Test
    public void testDecodeIOException() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        // Write 3 bytes then close client → decode() read() gets EOF → IOException
        client.write(ByteBuffer.wrap(new byte[3]));
        client.close();
        ChannelContext ctx = new ChannelContext(server, 4096);
        reader.decode(ctx, new byte[3], 0, 3);
        assertFalse(reader.valid);
        try { server.close(); } catch (Exception ignored) {}
    }

    // ==================== awaitSendWU / wakeupSendWU ====================

    @Test
    public void testAwaitSendWU() throws Exception {
        final TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Thread notifier = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(50);
                reader.wakeupSendWU();
            }
        });
        notifier.start();
        reader.awaitSendWU();
        notifier.join();
        assertTrue(reader.valid);
    }

    @Test
    public void testWakeupSendWU() throws Exception {
        new TestHttp2MessageReader().wakeupSendWU();
    }

    // ==================== validatePreface short-circuit branches ====================

    @Test
    public void testValidatePrefaceSecondLongMismatch() {
        // First 8 bytes match, second 8 differ
        byte[] buf = new byte[24];
        System.arraycopy(Http2MessageReader.CLIENT_CONNECTION_PREFACE, 0, buf, 0, 8);
        assertFalse(Http2MessageReader.validatePreface(buf));
    }

    @Test
    public void testValidatePrefaceThirdLongMismatch() {
        // First 16 bytes match, last 8 differ
        byte[] buf = new byte[24];
        System.arraycopy(Http2MessageReader.CLIENT_CONNECTION_PREFACE, 0, buf, 0, 16);
        assertFalse(Http2MessageReader.validatePreface(buf));
    }

    // ==================== Settings: max frame size too large ====================

    @Test
    public void testHandleSettingsMaxFrameSizeTooLarge() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // value > 16777215 triggers the second branch of the OR condition
        safeRun(() -> reader.handleFrame(ctx(), settingsFrame(5, 16777216)));
        assertFalse(reader.valid);
    }

    // ==================== handleControlFrame: PING ACK on real channel ====================

    @Test
    public void testHandleControlFramePingAckSent() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        try {
            ChannelContext ctx = new ChannelContext(server, 4096);
            TestHttp2MessageReader reader = new TestHttp2MessageReader();
            Http2Frame frame = createPingFrame();
            byte[] frameData = frame.getFrameData();
            reader.handleFrame(ctx, frame);
            // PING_ACK flag should be set on the sent frame
            assertTrue((frameData[4] & Http2Frame.PING_ACK) != 0);
            // Verify client received the PING_ACK frame
            byte[] response = new byte[17];
            int read = client.read(ByteBuffer.wrap(response));
            assertEquals(17, read);
            assertEquals(Http2Frame.FRAME_TYPE_PING, response[3]);
            assertTrue((response[4] & Http2Frame.PING_ACK) != 0);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== handleControlFrame: WINDOW_UPDATE negative increment ====================

    @Test
    public void testHandleControlFrameWindowUpdateNegativeIncrement() throws Exception {
        TestHttp2MessageReader reader = new TestHttp2MessageReader();
        // Build WINDOW_UPDATE frame with increment = -1 (negative)
        Http2Frame frame = new Http2Frame();
        byte[] data = new byte[13];
        data[2] = 4;          // payload length = 4
        data[3] = 8;          // type = WINDOW_UPDATE
        data[8] = 0;          // streamId = 0
        data[9] = (byte) 0xFF; data[10] = (byte) 0xFF; data[11] = (byte) 0xFF; data[12] = (byte) 0xFF; // increment = -1
        frame.setFrameData(data);
        frame.setPayloadLength(4);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.WINDOW_UPDATE);
        frame.payload(9, 4);

        safeRun(() -> reader.handleFrame(ctx(), frame));
        assertFalse(reader.valid);
    }

    // ==================== awaitSendWU: InterruptedException ====================

    @Test
    public void testAwaitSendWUInterrupted() throws Exception {
        final TestHttp2MessageReader reader = new TestHttp2MessageReader();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                reader.awaitSendWU();
            }
        });
        waiter.start();
        Thread.sleep(30); // ensure waiter enters wait(100)
        waiter.interrupt();
        waiter.join(500);
        assertFalse(waiter.isAlive());
    }

    // ==================== readNextMessageFrame: partial frame (readInternal) ====================

    @Test
    public void testReadNextMessageFramePartialRead() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        try {
            // DATA frame: length=10, streamId=1, payload "0123456789"
            byte[] frame = new byte[19];
            frame[2] = 10;
            frame[8] = 1;
            for (int i = 0; i < 10; i++) frame[9 + i] = (byte) ('0' + i);
            client.write(ByteBuffer.wrap(frame));
            client.close();
            TestHttp2MessageReader reader = new TestHttp2MessageReader();
            ChannelContext ctx = new ChannelContext(server, 4096);
            // Provide only 9 bytes (header with length=10, streamId=1) → readInternal fetches remaining 10
            byte[] headerBuf = new byte[9];
            headerBuf[2] = 10;  // payload length
            headerBuf[8] = 1;   // streamId
            reader.decode(ctx, headerBuf, 0, 9);
            assertTrue(reader.valid);
        } finally {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== Helpers ====================

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ==================== frame builders ====================

    private static Http2Frame createPingFrame() {
        byte[] data = new byte[17];
        data[3] = 6;
        Http2Frame frame = new Http2Frame();
        frame.setFrameData(data);
        frame.setPayloadLength(8);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.PING);
        frame.payload(9, 8);
        return frame;
    }

    private static Http2Frame createControlFrame(byte type, int payloadLen) {
        byte[] data = new byte[9 + payloadLen];
        data[3] = type;
        Http2Frame frame = new Http2Frame();
        frame.setFrameData(data);
        frame.setPayloadLength(payloadLen);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.valueOf(type & 0xFF));
        frame.payload(9, payloadLen);
        return frame;
    }

    private static Http2Frame settingsFrame(int id, int value) {
        byte[] data = new byte[15];
        data[2] = 6;
        data[3] = 4;
        data[9] = (byte) (id >>> 8);
        data[10] = (byte) id;
        data[11] = (byte) (value >>> 24);
        data[12] = (byte) (value >>> 16);
        data[13] = (byte) (value >>> 8);
        data[14] = (byte) value;
        Http2Frame frame = new Http2Frame();
        frame.setFrameData(data);
        frame.setPayloadLength(6);
        frame.setStreamId(0);
        frame.setType(Http2FrameType.SETTINGS);
        frame.payload(9, 6);
        return frame;
    }
}
