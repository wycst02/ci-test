package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP/2 response implementation.
 * <p>
 * Sends response via HTTP/2 binary frames (HEADERS + DATA).
 * HPACK encoding is used for header compression.
 *
 * @author wangyc
 */
public class Http2Response extends HttpCompleteResponse {

    static final String SERVER_VALUE = HTTPServer.SERVER + "/" + HTTPServer.VERSION;

    // Pre-encoded HPACK prefix for 103 Early Hints:
    //   literal name ref to index 8 (":status") + "103" + literal inline name "link"
    //   Hex: 48 03 31 30 33 40 04 6c 69 6e 6b
    private static final byte[] _H2_EARLY_HINTS_PREFIX = {
        0x48,                                    // never-indexed, name index 8 (":status")
        0x03,                                    // value length = 3
        0x31, 0x30, 0x33,                       // "103"
        0x40,                                    // never-indexed, inline name
        0x04,                                    // name length = 4
        0x6c, 0x69, 0x6e, 0x6b                  // "link"
    };

    final Http2Stream stream;
    final Map<String, String> h2Headers = new HashMap<String, String>();

    public Http2Response(Http2Request request, Http2Stream stream, ChannelContext ctx) {
        super(request, ctx);
        this.stream = stream;
    }

    // ==================== HttpResponse interface ====================

    @Override
    public HttpVersion getHttpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public void addHeader(String key, Serializable value) {
        h2Headers.put(key.toLowerCase(), String.valueOf(value));
    }

    @Override
    public void setHeader(String key, Serializable value) {
        h2Headers.put(key.toLowerCase(), String.valueOf(value));
    }

    @Override
    public Serializable getHeader(String key) {
        return h2Headers.get(key.toLowerCase());
    }

    @Override
    public void removeHeader(String key) {
        h2Headers.remove(key.toLowerCase());
    }

    @Override
    public void write(byte[] buf, int offset, int count) throws IOException {
        if (committed || count <= 0) return;
        if (!headersSent) writeHeaders(Http2Frame.END_HEADERS);
        if (bodyBuf.size() + count > HttpConf.BODY_MEMORY_THRESHOLD) {
            sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), false);
            bodyBuf.clear();
            sendChunkedData(buf, offset, count, false);
        } else {
            bodyBuf.write(buf, offset, count);
        }
    }

    @Override
    public void flush() throws IOException {
        if (committed) return;
        if (!headersSent) writeHeaders(Http2Frame.END_HEADERS);
        if (!bodyBuf.isEmpty()) {
            sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), false);
            bodyBuf.clear();
        }
        flushCtx();
    }

    @Override
    public void commit() throws IOException {
        if (committed) return;
        committed = true;
        if (!headersSent) writeHeaders();
        if (!bodyBuf.isEmpty()) {
            sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), true);
            bodyBuf.clear();
        }
        flushCtx();
    }

    private void sendChunkedData(byte[] buf, int offset, int len, boolean endStream) throws IOException {
        if (len <= 0) return;
        int chunkSize = stream.sendChunkSize();
        int remaining = len;
        int off = offset;
        // Reusable buffer: create once, reuse for all chunks
        ByteBuffer frame = stream.createFrameBuffer(9 + chunkSize, chunkSize, Http2Frame.FRAME_TYPE_DATA, 0);
        while (remaining > chunkSize) {
            frame.position(9);
            frame.put(buf, off, chunkSize);
            frame.flip();
            stream.writeDataFrame(frame, chunkSize);
            off += chunkSize;
            remaining -= chunkSize;
        }
        // Last chunk: patch length and END_STREAM flag in-place
        int lastLen = remaining;
        int flags = endStream ? Http2Frame.END_STREAM : 0;
        frame.put(0, (byte) (lastLen >> 16))
             .put(1, (byte) (lastLen >> 8))
             .put(2, (byte) lastLen)
             .put(4, (byte) flags)
             .position(9);
        frame.put(buf, off, lastLen);
        frame.flip();
        stream.writeDataFrame(frame, lastLen);
    }

    @Override
    public void handover() {
        committed = true;
        autoCommit = false;
        headersSent = true;
        stream.handover();
    }

    @Override
    public void earlyHints(String linkHeader) throws IOException {
        if (headersSent) return;
        HttpBuf buf = HttpBuf.of(64);
        // Pre-encoded HPACK prefix: :status: 103 + link header name
        buf.write(_H2_EARLY_HINTS_PREFIX);
        // HPACK-encoded link header value
        stream.writeHpackString(buf, linkHeader);

        java.nio.ByteBuffer frame = stream.createFrameBuffer(
                9 + buf.size(), buf.size(),
                Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS);
        frame.put(buf.toBytes());
        frame.flip();
        stream.writeFrame(frame);
    }

    // ==================== sendFile / 404 / GZIP ====================
    @Override
    protected void addCacheHeaders(long fileSize, long lastModified) throws IOException {
        addHeader(HttpHeaderNames.LAST_MODIFIED, HttpHeaderUtils.getDateHeaderValue(lastModified));
        addHeader(HttpHeaderNames.ETAG, generateETag(fileSize, lastModified));
    }

    @Override
    protected void sendFile0(File file, long fileSize, String mimeType, boolean shouldCompress) throws IOException {
        if (shouldCompress) {
            doStreamingCompressAndSend(file);
        } else {
            setContentLength(fileSize);
            doSendFileContent(file);
        }
    }

    @Override
    protected void notFound() throws IOException {
        status(HttpStatus.NOT_FOUND);
        bodyBuf.clear();
        commit();
    }

    protected void doSendFileContent(File file) throws IOException {
        commit();
        sendFileFramed(file);
    }

    protected void doSendCompressedResponse(byte[] compressedData) throws IOException {
        this.contentLength = compressedData.length;
        writeHeaders();
        sendChunkedData(compressedData, 0, compressedData.length, true);
        committed = true;
    }

    /**
     * Attempt automatic GZIP compression on buffered body data and send.
     * <p>
     * Only applies when: global GZIP enabled, client supports gzip,
     * headers not sent, body size >= GZIP_MIN_SIZE. Delegates to
     *
     * @return true if auto-compress and send succeeded
     * @throws IOException if compression or send fails
     */
    protected boolean attemptAutoGzipAndSend() throws IOException {
        boolean isShouldApplyAutoGzip = shouldApplyAutoGzip();
        if (isShouldApplyAutoGzip) {
            byte[] compressed = gzipCompress(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size());
            addHeader(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
            doSendCompressedResponse(compressed);
        }
        return isShouldApplyAutoGzip;
    }

    protected void doStreamingCompressAndSend(File file) throws IOException {
        final long fileSize = file.length();
        // Set Content-Encoding header for all compressed responses
        addHeader(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);

        // For small files, use in-memory compression (same as Http1)
        if (fileSize <= HttpConf.BODY_MEMORY_THRESHOLD) {
            byte[] fileContent = readFileContent(file, (int) fileSize);
            byte[] compressedContent = gzipCompress(fileContent);
            doSendCompressedResponse(compressedContent);
            return;
        }

        // Send headers
        writeHeaders(Http2Frame.END_HEADERS);
        // For large files, use streaming compression with HTTP/2 DATA frames (same as Http1 streamingGzipCompress)
        int chunkSize = stream.sendChunkSize();
        final ByteBuffer gzipFrame = ByteBuffer.allocate(9 + chunkSize);
        gzipFrame.putInt(5, stream.streamId);
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(
                new java.io.OutputStream() {
                    public void write(int b) throws IOException {
                        throw new IOException("single-byte write not supported");
                    }

                    public void write(byte[] b, int off, int len) throws IOException {
                        gzipFrame.put(0, (byte) (len >> 16))
                                 .put(1, (byte) (len >> 8))
                                 .put(2, (byte) len)
                                .clear().position(9);
                        gzipFrame.put(b, off, len);
                        gzipFrame.flip();
                        stream.writeDataFrame(gzipFrame, len);
                    }
                }, chunkSize);
        try {
            byte[] buffer = new byte[chunkSize];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) > 0) {
                gzip.write(buffer, 0, bytesRead);
            }
            gzip.finish();
        } finally {
            gzip.close();
            fis.close();
        }
        committed = true;
        flushCtx();
    }

    private void sendFileFramed(File file) throws IOException {
        FileChannel fc = new RandomAccessFile(file, "r").getChannel();
        long fileSize = file.length();
        long pos = 0;
        int chunkSize = stream.sendChunkSize();
        // Reusable buffer: pre-fill streamId (the only non-zero header field)
        ByteBuffer frame = ByteBuffer.allocate(9 + chunkSize)
                .putInt(5, stream.streamId);
        try {
            while (pos < fileSize) {
                frame.position(9).limit(9 + chunkSize);
                int read = fc.read(frame);
                if (read <= 0) break;
                pos += read;
                // Patch length and END_STREAM flag (only these change per iteration)
                frame.put(0, (byte) (read >> 16))
                     .put(1, (byte) (read >> 8))
                     .put(2, (byte) read)
                     .put(4, (byte) (pos == fileSize ? Http2Frame.END_STREAM : 0))
                     .flip();
                stream.writeDataFrame(frame, read);
            }
        } finally {
            fc.close();
        }
    }

    // ==================== Frame construction ====================
    private void writeHeaders() throws IOException {
        writeHeaders((bodyBuf.isEmpty() && contentLength <= 0) ? (Http2Frame.END_HEADERS | Http2Frame.END_STREAM) : Http2Frame.END_HEADERS);
    }

    private void writeHeaders(int flags) throws IOException {
        headersSent = true;
        byte[] payload = encodeHeaders();
        int len = payload.length;
        ByteBuffer frame = stream.createFrameBuffer(9 + len, len, Http2Frame.FRAME_TYPE_HEADERS, flags).put(payload);
        frame.flip();
        stream.writeFrame(frame);
    }
    // ==================== HPACK encoding ====================

    private byte[] encodeHeaders() {
        HttpBuf buf = HttpBuf.of(128);

        buf.write((byte) (0x40 | 8));
        stream.writeHpackString(buf, String.valueOf(status.code));

        if (contentType != null && !contentType.isEmpty()) {
            buf.write((byte) (0x40 | 31));
            stream.writeHpackString(buf, contentType);
        }

        if (contentLength >= 0) {
            buf.write((byte) (0x40 | 28));
            stream.writeHpackString(buf, String.valueOf(contentLength));
        }

        for (Map.Entry<String, String> entry : h2Headers.entrySet()) {
            String key = entry.getKey();
            if (HttpHeaderNames.CONTENT_TYPE.equals(key) || HttpHeaderNames.CONTENT_LENGTH.equals(key)) continue;
            buf.write((byte) 0x40);
            stream.writeHpackString(buf, key);
            stream.writeHpackString(buf, entry.getValue());
        }

        if (!h2Headers.containsKey(HttpHeaderNames.DATE)) {
            buf.write((byte) (0x40 | 33));
            stream.writeHpackString(buf, HttpHeaderUtils.getDateHeaderValue(System.currentTimeMillis()));
        }
        if (HttpConf.EXPOSE_SERVER_HEADER && !h2Headers.containsKey(HttpHeaderNames.SERVER)) {
            buf.write((byte) (0x40 | 54));
            stream.writeHpackString(buf, SERVER_VALUE);
        }

        return buf.toBytes();
    }

    void flushCtx() throws IOException {
        synchronized (ctx) {
            ctx.flush();
        }
    }
}
