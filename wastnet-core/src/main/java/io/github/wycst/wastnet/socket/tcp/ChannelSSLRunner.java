package io.github.wycst.wastnet.socket.tcp;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * SSL channel runner that handles SSL handshake and encrypted data processing.
 */
class ChannelSSLRunner extends ChannelRunner {

    private boolean isSSL;
    private boolean finishHandShake;
    SSLEngineContext sslEngineContext;

    ChannelSSLRunner(ChannelWorker worker, SocketChannel channel, NioConfig nioConfig, SSLContext sslCtx, String[] sslCipherSuites) throws IOException {
        this(worker, new ChannelSSLContext(channel, new SSLEngineContext(sslCtx, sslCipherSuites, nioConfig.getApplicationProtocols())), nioConfig);
    }

    /**
     * Constructor accepting a pre-built SSL channel context (supports client-mode SSL).
     */
    ChannelSSLRunner(ChannelWorker worker, ChannelSSLContext channelCtx, NioConfig nioConfig) throws IOException {
        super(worker, channelCtx, nioConfig);
    }



    @Override
    protected void beforeReady() {
        if (!finishHandShake) {
            ChannelSSLContext sslChannelContext = (ChannelSSLContext) ctx;
            this.sslEngineContext = sslChannelContext.sslEngineCtx;
            int handShakeFlag = sslHandShake(sslEngineContext);
            if (handShakeFlag == -1) {
                LOG.debug("SSL handshake failed");
                try {
                    release();
                } catch (IOException ignored) {
                }
                return;
            }
            this.isSSL = handShakeFlag == 1;
            this.finishHandShake = true;
        }
    }

    /*@Override
    protected void preparedRead() throws IOException {
        if (finishHandShake) {
            if (isSSL) {
                ctx.channelRead(sslEngineContext.packetInBuf);
            } else {
                super.preparedRead();
            }
        }
    }*/

    protected int handleChannelRead() throws IOException {
        if (isSSL) {
            SSLEngine sslEngine = sslEngineContext.sslEngine;
            ByteBuffer packetInBuf = sslEngineContext.packetInBuf;
            ByteBuffer applicationInBuf = sslEngineContext.applicationInBuf;
            // packetInBuf.clear(); // do not call clear()
            int bytesNum = ctx.channelRead(packetInBuf);
            if (bytesNum == -1) {
                return -1;
            } else {
                packetInBuf.flip();
                SSLEngineResult res;
                int position = applicationInBuf.position();
                if (position > 0) {
                    applicationInBuf.compact();
                }
                do {
                    res = sslEngine.unwrap(packetInBuf, applicationInBuf);
                } while (res.getStatus() == SSLEngineResult.Status.OK);
                packetInBuf.compact();
                applicationInBuf.flip();
                read(applicationInBuf);
                applicationInBuf.clear();
                return 1;
            }
        } else {
            return super.handleChannelRead();
        }
    }

    /**
     * Check if it might be plaintext message
     * <p>
     * Each SSL handshake message starts with a type attribute of one byte,
     * and a length attribute of three bytes indicating the message length.
     * The first five bytes of an SSL record are the header. The first byte of the header is 22, indicating the handshake protocol, the next two bytes are the version, and the last two bytes are the remaining length of the entire SSL record
     *
     * <p> Here only checking if the packet starts with 22 can basically filter out all non-SSL data
     *
     * @param buf buf
     * @return true: possibly plaintext message, false: definitely not plaintext message
     */
    boolean isMaybePlaintext(ByteBuffer buf) {
        byte firstByte = buf.array()[0];
        if (firstByte != 22) {
            return true;
        }
        return false;
    }

    /**
     * SSL handshake
     *
     * @param sslEngineContext sslEngineContext
     * @return 0: not finish handshake (plain text), 1: finish handshake, -1: close/error
     */
    private int sslHandShake(SSLEngineContext sslEngineContext) {
        try {
            ByteBuffer packetInBuf = sslEngineContext.packetInBuf;
            int size = ctx.channelRead(packetInBuf);
            // the first time data is read after an SSL connection is completed, the packetInBuf must be empty
            // if size is 0, read until size is not 0
            long handshakeTimeoutMs = nioConfig.getSslHandshakeTimeoutMs();
            long startTime = System.currentTimeMillis();
            while (size == 0) {
                if (handshakeTimeoutMs > 0 && System.currentTimeMillis() - startTime > handshakeTimeoutMs) {
                    LOG.debug("SSL handshake timeout");
                    return -1;
                }
                Thread.sleep(5);
                size = ctx.channelRead(packetInBuf);
            }
            if (size == -1) {
                return -1;
            }
            // Only check for plaintext fallback if explicitly allowed
            if (nioConfig.isAllowPlaintextWhenSslEnabled() && isMaybePlaintext(packetInBuf)) {
                sslEngineContext.setDisabled(true);
                int capacity = packetInBuf.capacity();
                do {
                    packetInBuf.flip();
                    read(packetInBuf);
                    packetInBuf.clear();
                    if (size < capacity) {
                        return 0;
                    }
                    size = ctx.channelRead(packetInBuf);
                    if (size == -1) return -1;
                } while (true);
            }
            // Proceed with SSL handshake (packetInBuf already contains pre-read data)
            ((ChannelSSLContext) ctx).doHandshake();
            return 1;
        } catch (Throwable e) {
            if (nioConfig.isPrintSSLErrorLog()) {
                e.printStackTrace();
            }
            return -1;
        }
    }
}
