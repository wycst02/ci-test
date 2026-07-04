package io.github.wycst.wastnet.http.h2;

/**
 * HTTP/2 frame structure.
 *
 * @author wangyc
 */
public final class Http2Frame {

    // Frame type codes
    public static final byte FRAME_TYPE_DATA = 0x00;
    public static final byte FRAME_TYPE_HEADERS = 0x01;
    public static final byte FRAME_TYPE_PRIORITY = 0x02;
    public static final byte FRAME_TYPE_RST_STREAM = 0x03;
    public static final byte FRAME_TYPE_SETTINGS = 0x04;
    public static final byte FRAME_TYPE_PUSH_PROMISE = 0x05;
    public static final byte FRAME_TYPE_PING = 0x06;
    public static final byte FRAME_TYPE_GOAWAY = 0x07;
    public static final byte FRAME_TYPE_WINDOW_UPDATE = 0x08;
    public static final byte FRAME_TYPE_CONTINUATION = 0x09;


    // Frame flags (bit positions vary by frame type — see RFC 7540)
    /**
     * END_STREAM (bit 0), {@code 0x01}.
     * <p>
     * Used by: {@code DATA}, {@code HEADERS}.
     * Indicates that the frame is the last in the stream.
     */
    static final int END_STREAM = 0x01;
    /**
     * END_HEADERS (bit 2), {@code 0x04}.
     * <p>
     * Used by: {@code HEADERS}, {@code CONTINUATION}.
     * Indicates that the header block fragment is the last.
     */
    static final int END_HEADERS = 0x04;
    /**
     * PADDED (bit 3), {@code 0x08}.
     * <p>
     * Used by: {@code DATA}, {@code HEADERS}, {@code PUSH_PROMISE}.
     * Indicates that the frame is padded.
     */
    static final int PADDED = 0x08;

    /**
     * PRIORITY (bit 5), {@code 0x20}.
     * <p>
     * Used by: {@code HEADERS}.
     * Indicates the presence of the stream dependency / weight field.
     */
    static final int PRIORITY = 0x20;
    /**
     * PING ACK (bit 7), {@code 0x80}.
     * <p>
     * Used by: {@code PING} (RFC 7540 §6.7).
     * The PING response MUST have this flag set.
     */
    static final int PING_ACK = 0x80;
    /**
     * SETTINGS ACK (bit 0), {@code 0x01}.
     * <p>
     * Used by: {@code SETTINGS} (RFC 7540 §6.5).
     * When set, the payload MUST be empty.
     */
    static final int SETTINGS_ACK = 0x01;

    /**
     * 24-bit unsigned integer, payload length
     */
    private int payloadLength;

    /**
     * Frame type, 8 bits
     */
    private Http2FrameType type;

    /**
     * Frame flags, 8 bits
     */
    private int flags;

    /**
     * Stream identifier, 31-bit unsigned integer
     */
    private int streamId;

    /**
     * Complete frame data (9-byte header + payload)
     */
    byte[] frameData;

    /**
     * Actual payload offset (skipping PADDED/PRIORITY overhead)
     */
    int payloadActualOffset;
    /**
     * Actual payload length (excluding trailing padding)
     */
    int payloadActualLength;

    /**
     * Get the payload length of this frame.
     * <p>
     * HTTP/2 frame structure:
     * <pre>
     * +-----------------------------------------------+
     * |                 Length (24 bits)              |  3 bytes (payload length)
     * +--------+--------+---------------------------+
     * |  Type  | Flags  |     Stream Identifier      |  5 bytes
     * +--------+--------+---------------------------+
     * |              Payload (Length bytes)            |
     * +-----------------------------------------------+
     *        9 bytes header         +   payload
     * </pre>
     * <p>
     * Note: This method returns only the payload length, not the total frame length.
     * Total frame length = payload length + 9 (header size).
     *
     * @return payload length in bytes (not including the 9-byte header)
     */
    public int getPayloadLength() {
        return payloadLength;
    }

    /**
     * Get the total frame length including header.
     *
     * @return total frame length in bytes (header + payload)
     */
    public int getFrameLength() {
        return frameData.length;
    }

    /**
     * Get the actual payload offset in frame data.
     *
     * @return payload offset (skipping PADDED/PRIORITY overhead)
     */
    public int getPayloadActualOffset() {
        return payloadActualOffset;
    }

    /**
     * Set the actual payload offset in frame data.
     *
     * @param payloadActualOffset payload offset
     */
    public void setPayloadActualOffset(int payloadActualOffset) {
        this.payloadActualOffset = payloadActualOffset;
    }

    /**
     * Get the actual payload length.
     *
     * @return payload length (excluding padding)
     */
    public int getPayloadActualLength() {
        return payloadActualLength;
    }

    /**
     * Set the actual payload length.
     *
     * @param payloadActualLength payload length
     */
    public void setPayloadActualLength(int payloadActualLength) {
        this.payloadActualLength = payloadActualLength;
    }

    /**
     * Get the complete frame data as a byte array.
     * <p>
     * This includes the 9-byte header (Length + Type + Flags + Stream ID) and the payload.
     *
     * @return complete frame data
     */
    public byte[] getFrameData() {
        return frameData;
    }

    /**
     * Convert byte array to hex string.
     */
    private static String toHexString(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            sb.append(String.format("%02x", data[i]));
        }
        return sb.toString();
    }

    /**
     * Dump frame data in a human-readable hex format.
     * <p>
     * Example output:
     * <pre>
     * +-----------------------------------------------------------------------------------+
     * | [HEADERS] streamId=1 length=12 flags=0x05                                        |
     * +-----------------------------------------------------------------------------------+
     * | Frame:   00000c0105000000018362617369632f68746d6c                                  |
     * | Head:    00000c010500000001                                                       |
     * | Payload: 8362617369632f68746d6c                                                   |
     * |                                                                                   |
     * | Offset  | 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f | ASCII            |
     * | --------+----------------------------------------------------------------+---------+
     * | 0x0000  | 00 00 0c 01 05 00 00 00  01 83 62 61 73 69 63 2f | .....basic/|    |
     * | 0x0010  | 68 74 6d 6c                                     | html     |    |
     * +---------+----------------------------------------------------------------+---------+
     * </pre>
     *
     * @return hex dump string
     */
    public String toHexDump() {
        byte[] data = frameData;
        StringBuilder sb = new StringBuilder();

        // Leading newline for separation
        sb.append("\n");

        String typeName = type != null ? type.name() : "UNKNOWN";
        String headerInfo = String.format("[%s] streamId=%d length=%d flags=0x%02x", typeName, streamId, payloadLength, flags);

        // Build hex dump rows
        int bytesPerLine = 16;
        int totalLength = data.length;

        // Calculate hex dump header and row widths
        // Format: " Offset  | 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f | ASCII"
        // Data row: offset(8) + " | "(3) + hex bytes(49) + "| "(2) + ascii(16) = 78
        String hexDumpHeader = " Offset  | 00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f | ASCII";
        // Separator will be calculated after contentWidth is determined
        String hexDumpSep = null;

        // Determine box width based on Frame length
        // Calculate hex dump row width: offset(8) + " | "(3) + hex(16*3+1=49) + "| "(2) + ascii(16) = 78
        int hexDumpRowWidth = 8 + 3 + 49 + 2 + 16;
        int frameWidth = 10 + data.length * 2;  // "Frame:   " + hex bytes
        int width = Math.max(headerInfo.length(), frameWidth);
        width = Math.max(width, hexDumpRowWidth);
        width = width + 4; // "| " + " |" = 4

        // Calculate hexDumpSep now that contentWidth is known
        hexDumpSep = repeat("-", width - 4);

        // Build output
        String border = "+" + repeat("-", width - 2) + "+";
        String innerPrefix = "| ";
        String innerSuffix = " |";

        sb.append(border).append("\n");

        // Info line
        int contentWidth = width - 4;
        sb.append(innerPrefix);
        sb.append(String.format("%-" + contentWidth + "s", headerInfo));
        sb.append(innerSuffix).append("\n");

        sb.append(border).append("\n");

        // Frame hex
        sb.append(innerPrefix);
        String frameHex = "Frame:   " + toHexString(data);
        sb.append(String.format("%-" + contentWidth + "s", frameHex));
        sb.append(innerSuffix).append("\n");

        // Head hex
        sb.append(innerPrefix);
        String headHex = "Head:    ";
        for (int i = 0; i < 9; i++) {
            headHex += String.format("%02x", data[i]);
        }
        sb.append(String.format("%-" + contentWidth + "s", headHex));
        sb.append(innerSuffix).append("\n");

        // Payload hex
        if (payloadLength > 0) {
            sb.append(innerPrefix);
            StringBuilder payloadHex = new StringBuilder("Payload: ");
            for (int i = 9; i < data.length; i++) {
                payloadHex.append(String.format("%02x", data[i]));
            }
            sb.append(String.format("%-" + contentWidth + "s", payloadHex.toString()));
            sb.append(innerSuffix).append("\n");
        }

        // Separator
        sb.append(border).append("\n");

        // Hex dump section
        sb.append(innerPrefix);
        // Pad or truncate to contentWidth
        String hdHeader = hexDumpHeader;
        if (hdHeader.length() < contentWidth) {
            hdHeader = hdHeader + repeat(" ", contentWidth - hdHeader.length());
        } else if (hdHeader.length() > contentWidth) {
            hdHeader = hdHeader.substring(0, contentWidth);
        }
        sb.append(hdHeader);
        sb.append(innerSuffix).append("\n");

        sb.append(innerPrefix);
        String hdSep = hexDumpSep;
        if (hdSep.length() < contentWidth) {
            hdSep = hdSep + repeat(" ", contentWidth - hdSep.length());
        } else if (hdSep.length() > contentWidth) {
            hdSep = hdSep.substring(0, contentWidth);
        }
        sb.append(hdSep);
        sb.append(innerSuffix).append("\n");

        // Data rows
        for (int i = 0; i < totalLength; i += bytesPerLine) {
            // Build the line content first, then pad to contentWidth
            sb.append(innerPrefix);
            StringBuilder line = new StringBuilder();

            // Offset (8 hex digits)
            line.append(String.format("%08x", i));
            line.append(" | ");

            // Hex bytes (16 bytes per line)
            for (int j = 0; j < bytesPerLine; j++) {
                if (j == 8) line.append(" ");
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    line.append(String.format("%02x ", b));
                } else {
                    line.append("   ");
                }
            }
            line.append("| ");

            // ASCII (16 chars)
            for (int j = 0; j < bytesPerLine; j++) {
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    if (b >= 32 && b <= 126) {
                        line.append((char) b);
                    } else {
                        line.append('.');
                    }
                } else {
                    line.append(' ');
                }
            }

            // Pad line to contentWidth
            String lineStr = String.format("%-" + contentWidth + "s", line.toString());
            sb.append(lineStr);
            sb.append(innerSuffix).append("\n");
        }

        sb.append(border).append("\n");

        return sb.toString();
    }

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * Dump byte array in a human-readable hex format.
     *
     * @param data the byte array to dump
     * @return hex dump string
     */
    public static String toHexDump(byte[] data) {
        return toHexDump(data, 16);
    }

    /**
     * Dump byte array with specified bytes per line.
     *
     * @param data         the byte array to dump
     * @param bytesPerLine number of bytes per line
     * @return hex dump string
     */
    public static String toHexDump(byte[] data, int bytesPerLine) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int totalLength = data.length;

        // Column headers
        int offsetWidth = Math.max(4, Integer.toHexString(totalLength).length());
        StringBuilder headerOffset = new StringBuilder("Offset");
        StringBuilder headerHex = new StringBuilder();
        StringBuilder headerAscii = new StringBuilder("ASCII");
        for (int i = 0; i < bytesPerLine; i++) {
            if (i == bytesPerLine / 2) {
                headerHex.append("  ");
            }
            headerHex.append(String.format("%02x ", i));
        }
        headerOffset.append(String.format("%" + (offsetWidth - 6) + "s", ""));
        headerAscii.insert(0, String.format("%" + (offsetWidth + 3 + bytesPerLine * 3 + 2) + "s", ""));

        sb.append(headerOffset).append(" | ").append(headerHex).append("| ").append(headerAscii).append("\n");

        // Separator
        String sep = "-";
        String lineSep = String.format("%" + (offsetWidth + 2) + "s", sep).replace(' ', '-')
                + "-+-" + String.format("%-" + (bytesPerLine * 3 + bytesPerLine / 2) + "s", sep).replace(' ', '-')
                + "-+-" + String.format("%" + bytesPerLine + "s", sep).replace(' ', '-');
        sb.append(lineSep).append("\n");

        // Data
        for (int i = 0; i < totalLength; i += bytesPerLine) {
            // Offset
            sb.append(String.format("%0" + offsetWidth + "x", i));
            sb.append(" | ");

            // Hex bytes
            StringBuilder hexPart = new StringBuilder();
            StringBuilder asciiPart = new StringBuilder();
            for (int j = 0; j < bytesPerLine; j++) {
                int index = i + j;
                if (index < totalLength) {
                    int b = data[index] & 0xFF;
                    if (j == bytesPerLine / 2) {
                        hexPart.append(" ");
                    }
                    hexPart.append(String.format("%02x ", b));
                    // ASCII
                    if (b >= 32 && b <= 126) {
                        asciiPart.append((char) b);
                    } else {
                        asciiPart.append('.');
                    }
                } else {
                    if (j == bytesPerLine / 2) {
                        hexPart.append(" ");
                    }
                    hexPart.append("   ");
                }
            }
            sb.append(String.format("%-" + (bytesPerLine * 3 + bytesPerLine / 2) + "s", hexPart.toString()));
            sb.append("| ");
            sb.append(asciiPart);
            sb.append("\n");
        }
        sb.append(lineSep);

        return sb.toString();
    }

    /**
     * Set the payload length.
     *
     * @param payloadLength payload length in bytes
     */
    public void setPayloadLength(int payloadLength) {
        this.payloadLength = payloadLength;
    }

    /**
     * Get the frame type.
     *
     * @return frame type
     */
    public Http2FrameType getType() {
        return type;
    }

    /**
     * Set the frame type.
     *
     * @param type frame type
     */
    public void setType(Http2FrameType type) {
        this.type = type;
    }

    /**
     * Get the frame flags.
     *
     * @return frame flags
     */
    public int getFlags() {
        return flags;
    }

    /**
     * Set the frame flags.
     *
     * @param flags frame flags
     */
    public void setFlags(int flags) {
        this.flags = flags;
    }

    /**
     * Get the reserved bit value.
     *
     * @return reserved bit, always 0
     */
    public int getReserved() {
        return 0;
    }

    /**
     * Get the stream identifier.
     *
     * @return stream ID (31-bit unsigned integer)
     */
    public int getStreamId() {
        return streamId;
    }

    /**
     * Set the stream identifier.
     *
     * @param streamId stream ID
     */
    public void setStreamId(int streamId) {
        this.streamId = streamId;
    }

    /**
     * Set the complete frame data.
     *
     * @param frameData frame data (9-byte header + payload)
     */
    public void setFrameData(byte[] frameData) {
        this.frameData = frameData;
    }

    /**
     * Set the payload offset and length.
     *
     * @param payloadOffset actual payload offset in frame data
     * @param payloadLen    actual payload length
     */
    public void payload(int payloadOffset, int payloadLen) {
        this.payloadActualOffset = payloadOffset;
        this.payloadActualLength = payloadLen;
    }

    /**
     * Create an Http2Frame from a direct ByteBuffer.
     * <p>
     * Used to unify server-side outgoing frame DEBUG output with client-side incoming frame format.
     *
     * @param buf ByteBuffer positioned at 0, limit = 9 + payloadLength
     * @return a new Http2Frame representing the frame
     */
    static Http2Frame fromByteBuffer(java.nio.ByteBuffer buf) {
        byte[] data = new byte[buf.limit()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        Http2Frame frame = new Http2Frame();
        frame.frameData = data;
        frame.payloadLength = data.length - 9;
        frame.type = Http2FrameType.valueOf(data[3] & 0xff);
        frame.flags = data[4] & 0xff;
        frame.streamId = ((data[5] & 0xff) << 24) | ((data[6] & 0xff) << 16) | ((data[7] & 0xff) << 8) | (data[8] & 0xff);
        return frame;
    }

    /**
     * Check if a specific flag is set.
     *
     * @param flag flag bit mask
     * @return true if the flag is set
     */
    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Check if all specified flags are set.
     *
     * @param flags flag bit mask
     * @return true if all flags are set
     */
    public boolean hasFlags(int flags) {
        return (this.flags & flags) == flags;
    }

    /**
     * Check if the END_STREAM flag is set.
     *
     * @return true if the END_STREAM flag is set
     */
    public boolean isEndStream() {
        return hasFlag(END_STREAM);
    }

    /**
     * Check if the END_HEADERS flag is set.
     *
     * @return true if the END_HEADERS flag is set
     */
    public boolean isEndHeaders() {
        return hasFlag(END_HEADERS);
    }
}
