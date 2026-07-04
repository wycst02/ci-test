package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.exception.SocketException;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;

/**
 * SSL channel context that extends ChannelContext with SSL/TLS support.
 */
public final class ChannelSSLContext extends ChannelContext {
    final SSLEngineContext sslEngineCtx;

    /**
     * Constructor for SSL channel context
     *
     * @param channel      the socket channel
     * @param sslEngineCtx the SSL engine context
     * @throws IOException if initialization fails
     */
    public ChannelSSLContext(SocketChannel channel, SSLEngineContext sslEngineCtx) throws IOException {
        super(channel, 0);
        this.sslEngineCtx = sslEngineCtx;
    }

    /**
     * Constructor for SSL channel context
     *
     * @param id           the channel id
     * @param channel      the socket channel
     * @param sslEngineCtx the SSL engine context
     * @throws IOException if initialization fails
     */
    public ChannelSSLContext(long id, SocketChannel channel, SSLEngineContext sslEngineCtx) throws IOException {
        super(id, channel);
        this.sslEngineCtx = sslEngineCtx;
    }

    static final TrustManager[] TRUST_ALL_MANAGERS = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
    };

    /**
     * Create a client-mode SSL channel context and perform handshake.
     *
     * @param id      the channel id
     * @param channel the connected socket channel
     * @return the SSL channel context after successful handshake
     * @throws IOException if handshake fails
     */
    public static ChannelSSLContext createClientContext(long id, SocketChannel channel) throws IOException {
        return createClientContext(id, channel, null);
    }

    public static ChannelSSLContext createClientContext(long id, SocketChannel channel, String[] applicationProtocols) throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, TRUST_ALL_MANAGERS, null);
            SSLEngineContext sslEngineCtx = new SSLEngineContext(sslContext, null, applicationProtocols, true);
            ChannelSSLContext ctx = new ChannelSSLContext(id, channel, sslEngineCtx);
            ctx.doHandshake();
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to create client SSL context", e);
        }
    }

    /**
     * Perform SSL handshake (blocking).
     * If packetInBuf already contains data (e.g. pre-read by caller), it will be consumed first.
     *
     * @throws IOException if handshake fails
     */
    void doHandshake() throws IOException {
        SSLEngine sslEngine = sslEngineCtx.sslEngine;
        ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
        ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
        ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
        ByteBuffer applicationOutBuf = sslEngineCtx.applicationOutBuf;

        sslEngine.beginHandshake();
        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        SSLEngineResult res;

        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (channelRead(packetInBuf) == -1) {
                        throw new SSLException("SSL handshake: connection closed");
                    }
                    packetInBuf.flip();
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                    packetInBuf.compact();
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException("SSL handshake closed during unwrap");
                    }
                    handshakeStatus = res.getHandshakeStatus();
                    break;

                case NEED_WRAP:
                    packetOutBuf.clear();
                    res = sslEngine.wrap(applicationOutBuf, packetOutBuf);
                    packetOutBuf.flip();
                    channelWrite(packetOutBuf);
                    if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException("SSL handshake closed during wrap");
                    }
                    handshakeStatus = res.getHandshakeStatus();
                    break;

                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
            }
        }
        packetOutBuf.clear();
    }

    @Override
    public int getWriteBufferSize() {
        if (sslEngineCtx.isDisabled()) {
            return super.getWriteBufferSize();
        } else {
            return sslEngineCtx.packetOutBuf.capacity();
        }
    }

    /**
     * Write data with SSL encryption
     *
     * @param buf the buffer containing data to write
     * @return always returns 0
     * @throws IOException if write operation fails
     */
    @Override
    public int write(ByteBuffer buf) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            channelWrite(buf);
            buf.clear();
        } else {
            SSLEngine sslEngine = sslEngineCtx.sslEngine;
            ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
            // Loop processing until all data is encrypted
            while (buf.hasRemaining()) {
                SSLEngineResult res = sslEngine.wrap(buf, packetOutBuf);
                SSLEngineResult.Status status = res.getStatus();
                if (!packetOutBuf.hasRemaining() || status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    flush();
                    continue;
                }
                if (status != SSLEngineResult.Status.OK) {
                    throw new SocketException("Unexpected exception, SSL encryption failed status: " + status);
                }
            }
        }
        return 0;
    }

    /**
     * Flush SSL encrypted data to channel
     *
     * @throws IOException if flush operation fails
     */
    @Override
    public void flush() throws IOException {
        if (sslEngineCtx.isDisabled()) {
            super.flush();
        } else {
            ByteBuffer packetOutBuf = sslEngineCtx.packetOutBuf;
            packetOutBuf.flip();
            channelWrite(packetOutBuf);
            packetOutBuf.clear();
        }
    }

    /**
     * Read decrypted data from SSL channel into buffer (non-blocking).
     * First consumes any remaining data in applicationInBuf, then
     * reads and unwraps network data from the channel.
     *
     * @param buf the buffer to read into
     * @return the number of bytes read, 0 if no data available, -1 if channel is closed
     * @throws IOException if read fails
     */
    @Override
    public int read(ByteBuffer buf) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            return channelRead(buf);
        }
        SSLEngine sslEngine = sslEngineCtx.sslEngine;
        ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
        ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
        int totalRead = 0;

        // First, consume any remaining data in applicationInBuf
        if (applicationInBuf.position() > 0) {
            applicationInBuf.flip();
            totalRead += transferTo(applicationInBuf, buf);
            if (applicationInBuf.hasRemaining()) {
                applicationInBuf.compact();
                return totalRead;
            }
            applicationInBuf.clear();
        }

        // Read and unwrap network data
        int n = channelRead(packetInBuf);
        if (packetInBuf.position() == 0 && n < 1) {
            return totalRead > 0 ? totalRead : n;
        }

        packetInBuf.flip();
        try {
            SSLEngineResult res;
            do {
                res = sslEngine.unwrap(packetInBuf, applicationInBuf);
            } while (res.getStatus() == SSLEngineResult.Status.OK);

            if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
                return totalRead > 0 ? totalRead : -1;
            }

            applicationInBuf.flip();
            totalRead += transferTo(applicationInBuf, buf);
            if (applicationInBuf.hasRemaining()) {
                applicationInBuf.compact();
            } else {
                applicationInBuf.clear();
            }
        } finally {
            packetInBuf.compact();
        }
        return totalRead;
    }

    /**
     * Transfer data from src ByteBuffer to dst ByteBuffer (partial transfer supported)
     *
     * @param src source buffer (must be in read mode)
     * @param dst destination buffer
     * @return number of bytes transferred
     */
    private int transferTo(ByteBuffer src, ByteBuffer dst) {
        if (!src.hasRemaining()) return 0;
        int toTransfer = Math.min(src.remaining(), dst.remaining());
        int oldLimit = src.limit();
        src.limit(src.position() + toTransfer);
        dst.put(src);
        src.limit(oldLimit);
        return toTransfer;
    }

    /**
     * Blocking read of SSL decrypted data until the specified length is filled
     *
     * @param b         target byte array
     * @param off       array starting offset
     * @param len       number of bytes to read
     * @param timeoutMs timeout in milliseconds
     * @return number of bytes successfully read, or -1 indicating end of stream
     * @throws IOException if read operation fails
     */
    @Override
    public int readFully(byte[] b, int off, final int len, long timeoutMs) throws IOException {
        if (sslEngineCtx.isDisabled()) {
            return super.readFully(b, off, len, timeoutMs);
        } else {
            long startTime = System.currentTimeMillis();
            ByteBuffer applicationInBuf = sslEngineCtx.applicationInBuf;
            int limit = applicationInBuf.limit();
            int position = applicationInBuf.position();
            int tlen = len;
            if (position > 0) {
                // just check if not the first and read
                int rem = limit - position;   // applicationInBuf.remaining()
                if (rem >= len) {
                    applicationInBuf.get(b, off, len);
                    return len;
                }
                // read all buffered data
                applicationInBuf.get(b, off, rem);
                off += rem;
                tlen -= rem;
                // switch to write mode
                applicationInBuf.clear();
            }
            SSLEngine sslEngine = sslEngineCtx.sslEngine;
            ByteBuffer packetInBuf = sslEngineCtx.packetInBuf;
            // packetInBuf.clear(); // do not call clear()
            while (true) {
                int bytesNum;
                // Inner loop: handle bytesNum=0 by blocking until data arrives
                while (true) {
                    bytesNum = channelRead(packetInBuf);
                    if (bytesNum == -1) {
                        return -1;
                    } else if (bytesNum > 0) {
                        break; // Exit inner loop when data is available
                    } else {
                        if(packetInBuf.position() == 0) {
                            // bytesNum == 0 and empty packetInBuf: wait for data
                            awaitReadableWithTimeout(startTime, timeoutMs);
                        } else break;
                    }
                }
                packetInBuf.flip();
                SSLEngineResult res;
                do {
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                } while (res.getStatus() == SSLEngineResult.Status.OK);

                packetInBuf.compact();
                applicationInBuf.flip();
                // position is zero after flip
                limit = applicationInBuf.limit();
                if (limit >= tlen) {
                    applicationInBuf.get(b, off, tlen);
                    return len;
                } else {
                    applicationInBuf.get(b, off, limit);
                    off += limit;
                    tlen -= limit;
                    // switch to write mode
                    applicationInBuf.clear();
                }

                // Check unwrap result status after processing data
                SSLEngineResult.Status status = res.getStatus();
                if (status == SSLEngineResult.Status.CLOSED) {
                    return -1;
                }
                /*if (status != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    throw new SSLException("SSL unwrap failed with unexpected status: " + status);
                }*/
            }
        }
    }

    /**
     * Check if this is an SSL channel
     *
     * @return true if SSL is enabled, false otherwise
     */
    @Override
    public boolean isSSL() {
        return !sslEngineCtx.isDisabled();
    }

    /**
     * Get the handshaked application protocol
     *
     * @return the application protocol, or null if not available
     */
    @Override
    public String getHandShakedApplicationProtocol() {
        if (isSSL()) {
            return RuntimeEnv.INSTANCE.getSSLApplicationProtocol(sslEngineCtx.sslEngine);
        } else {
            return null;
        }
    }
}
