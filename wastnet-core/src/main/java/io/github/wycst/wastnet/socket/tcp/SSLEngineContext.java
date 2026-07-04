package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.env.RuntimeEnv;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;

/**
 * SSL engine context containing SSLEngine and its associated buffers.
 */
public class SSLEngineContext {
    final SSLEngine sslEngine;
    final ByteBuffer packetInBuf;
    final ByteBuffer applicationInBuf;
    final ByteBuffer packetOutBuf;
    final ByteBuffer applicationOutBuf;

    boolean disabled;
    static final int HEADER_DELTA = 2048;

    public SSLEngineContext(SSLContext sslContext, String[] sslCipherSuites, String[] applicationProtocols) {
        this(sslContext, sslCipherSuites, applicationProtocols, false);
    }

    public SSLEngineContext(SSLContext sslContext, String[] sslCipherSuites, String[] applicationProtocols, boolean useClientMode) {
        sslEngine = sslContext.createSSLEngine();
        if (sslCipherSuites != null && sslCipherSuites.length > 0) {
            sslEngine.setEnabledCipherSuites(sslCipherSuites);
        }
        RuntimeEnv.INSTANCE.setApplicationProtocols(sslEngine, applicationProtocols);
        sslEngine.setUseClientMode(useClientMode);
        SSLSession session = sslEngine.getSession();
        packetInBuf = ByteBuffer.allocate(session.getPacketBufferSize());
        applicationInBuf = ByteBuffer.allocate(session.getApplicationBufferSize());
        packetOutBuf = ByteBuffer.allocate(session.getPacketBufferSize() + HEADER_DELTA);
        applicationOutBuf = ByteBuffer.allocate(session.getApplicationBufferSize());
        session.invalidate();
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }
}
