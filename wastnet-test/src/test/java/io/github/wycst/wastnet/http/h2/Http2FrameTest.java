package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Unit tests for {@link Http2Frame}.
 *
 * @author wangyc
 */
public class Http2FrameTest {

    @Test
    public void testCreateFrameFromScratch() {
        Http2Frame frame = new Http2Frame();
        frame.setType(Http2FrameType.DATA);
        frame.setStreamId(1);
        frame.setFlags(0x01);
        frame.setPayloadLength(10);
        Assertions.assertEquals(Http2FrameType.DATA, frame.getType());
        Assertions.assertEquals(1, frame.getStreamId());
        Assertions.assertEquals(0x01, frame.getFlags());
        Assertions.assertEquals(10, frame.getPayloadLength());
    }

    @Test
    public void testCreateDataFrame() {
        Http2Frame frame = new Http2Frame();
        frame.setType(Http2FrameType.DATA);
        frame.setStreamId(3);
        byte[] data = {0, 0, 5, 0, 0, 0, 0, 0, 3, 72, 101, 108, 108, 111};
        frame.setFrameData(data);
        frame.payload(9, 5);
        Assertions.assertEquals(5, frame.getPayloadActualLength());
        Assertions.assertEquals(9, frame.getPayloadActualOffset());
        Assertions.assertEquals(14, frame.getFrameLength());
    }

    @Test
    public void testFromByteBuffer() {
        byte[] frameBytes = new byte[14];
        frameBytes[0] = 0; frameBytes[1] = 0; frameBytes[2] = 5;
        frameBytes[3] = 0x01;
        frameBytes[4] = 0x04;
        frameBytes[5] = 0; frameBytes[6] = 0; frameBytes[7] = 0; frameBytes[8] = 1;
        frameBytes[9] = 0x01; frameBytes[10] = 0x02;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);

        Assertions.assertNotNull(frame);
        Assertions.assertEquals(5, frame.getPayloadLength());
        Assertions.assertEquals(Http2FrameType.HEADERS, frame.getType());
        Assertions.assertEquals(0x04, frame.getFlags());
        Assertions.assertEquals(1, frame.getStreamId());
    }

    @Test
    public void testHasFlag() {
        Http2Frame frame = new Http2Frame();
        frame.setFlags(0x05);
        Assertions.assertTrue(frame.hasFlag(0x01));
        Assertions.assertTrue(frame.hasFlag(0x04));
        Assertions.assertFalse(frame.hasFlag(0x08));
        Assertions.assertTrue(frame.hasFlags(0x01 | 0x04));
    }

    @Test
    public void testIsEndStream() {
        Http2Frame frame = new Http2Frame();
        frame.setFlags(0x01);
        Assertions.assertTrue(frame.isEndStream());
        frame.setFlags(0x00);
        Assertions.assertFalse(frame.isEndStream());
    }

    @Test
    public void testIsEndHeaders() {
        Http2Frame frame = new Http2Frame();
        frame.setFlags(0x04);
        Assertions.assertTrue(frame.isEndHeaders());
        frame.setFlags(0x00);
        Assertions.assertFalse(frame.isEndHeaders());
    }

    @Test
    public void testSettingsFrame() {
        byte[] frameBytes = new byte[15];
        frameBytes[0] = 0; frameBytes[1] = 0; frameBytes[2] = 6;
        frameBytes[3] = 0x04; // SETTINGS
        frameBytes[4] = 0x00;
        frameBytes[5] = 0; frameBytes[6] = 0; frameBytes[7] = 0; frameBytes[8] = 0;
        frameBytes[9] = 0; frameBytes[10] = 4; // SETTINGS_INITIAL_WINDOW_SIZE

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);
        Assertions.assertEquals(Http2FrameType.SETTINGS, frame.getType());
    }

    @Test
    public void testPingFrame() {
        byte[] frameBytes = new byte[17];
        frameBytes[3] = 0x06; // PING
        frameBytes[4] = 0x00;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);
        Assertions.assertEquals(Http2FrameType.PING, frame.getType());
        Assertions.assertEquals(8, frame.getPayloadLength());
    }

    @Test
    public void testGoAwayFrame() {
        byte[] frameBytes = new byte[17];
        frameBytes[3] = 0x07; // GOAWAY
        frameBytes[4] = 0x00;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        Http2Frame frame = Http2Frame.fromByteBuffer(buf);
        Assertions.assertEquals(Http2FrameType.GOAWAY, frame.getType());
    }
}
