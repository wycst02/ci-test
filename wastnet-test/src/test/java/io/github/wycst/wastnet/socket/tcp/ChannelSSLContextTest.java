package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.http.HTTPServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ChannelSSLContext}.
 * <p>
 * Covers disabled (plaintext fallback) paths for all SSL methods,
 * constructor variants, and static factory methods.
 */
public class ChannelSSLContextTest {

    private ServerSocket serverSocket;
    private Socket serverSide;
    private SocketChannel clientChannel;

    @BeforeEach
    public void setupConnectedChannel() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        new Thread(() -> {
            try { serverSide = serverSocket.accept(); } catch (Exception e) {}
        }, "ssl-test-accept").start();

        clientChannel = SocketChannel.open();
        clientChannel.connect(new InetSocketAddress("127.0.0.1", port));
        clientChannel.configureBlocking(false);
        // Wait for server to accept
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (clientChannel != null) try { clientChannel.close(); } catch (Exception e) {}
        if (serverSide != null) try { serverSide.close(); } catch (Exception e) {}
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception e) {}
    }

    private static SSLEngineContext createDisabledEngineCtx() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngineContext ctx = new SSLEngineContext(sslContext, null, null);
        ctx.setDisabled(true);
        return ctx;
    }

    // ==================== Constructors ====================

    @Test
    public void testConstructorWithSocketChannel() throws Exception {
        SSLEngineContext sslCtx = createDisabledEngineCtx();
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, sslCtx);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testConstructorWithId() throws Exception {
        SSLEngineContext sslCtx = createDisabledEngineCtx();
        ChannelSSLContext ctx = new ChannelSSLContext(100L, clientChannel, sslCtx);
        Assertions.assertNotNull(ctx);
    }

    // ==================== isSSL ====================

    @Test
    public void testIsSSLReturnsTrueWhenEnabled() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngineContext sslCtx = new SSLEngineContext(sslContext, null, null);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, sslCtx);
        Assertions.assertTrue(ctx.isSSL());
    }

    @Test
    public void testIsSSLReturnsFalseWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertFalse(ctx.isSSL());
    }

    // ==================== Disabled path coverage ====================

    @Test
    public void testGetWriteBufferSizeWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertEquals(0, ctx.getWriteBufferSize());
    }

    @Test
    public void testGetHandShakedApplicationProtocolWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertNull(ctx.getHandShakedApplicationProtocol());
    }

    @Test
    public void testFlushWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ctx.flush(); // should not throw when disabled
    }

    @Test
    public void testWriteWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putInt(12345);
        buf.flip();
        ctx.write(buf);
        // should not throw
    }

    @Test
    public void testReadWhenDisabledReturnsZero() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ByteBuffer buf = ByteBuffer.allocate(16);
        int n = ctx.read(buf);
        // connected channel with no data → returns 0
        Assertions.assertEquals(0, n);
    }

    @Test
    public void testReadFullyWhenDisabledReturnsMinusOneOrTimeout() throws Exception {
        // Close server side so channelRead eventually returns -1
        if (serverSide != null) {
            serverSide.shutdownOutput();
            serverSide.close();
        }
        Thread.sleep(100);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        byte[] b = new byte[4];
        try {
            int n = ctx.readFully(b, 0, 4, 1000);
            Assertions.assertEquals(-1, n);
        } catch (java.net.SocketTimeoutException e) {
            // Timeout is also acceptable (FIN not yet received)
        }
    }

    // ==================== ChannelSSLContext$1 (TrustManager anonymous class) ====================

    @Test
    public void testTrustAllManagers() throws Exception {
        javax.net.ssl.X509TrustManager tm = (javax.net.ssl.X509TrustManager) ChannelSSLContext.TRUST_ALL_MANAGERS[0];
        Assertions.assertNotNull(tm.getAcceptedIssuers());
        Assertions.assertEquals(0, tm.getAcceptedIssuers().length);
        tm.checkClientTrusted(null, null);
        tm.checkServerTrusted(null, null);
    }

    // ==================== transferTo empty src ====================

    @Test
    public void testTransferToEmptySrc() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        java.lang.reflect.Method m = ChannelSSLContext.class.getDeclaredMethod("transferTo", ByteBuffer.class, ByteBuffer.class);
        m.setAccessible(true);
        ByteBuffer emptySrc = ByteBuffer.allocate(0);
        ByteBuffer dst = ByteBuffer.allocate(10);
        int n = (int) m.invoke(ctx, emptySrc, dst);
        Assertions.assertEquals(0, n);
    }

    // ==================== read with applicationInBuf remaining data ====================

    @Test
    public void testReadWithApplicationInBufRemaining() throws Exception {
        javax.net.ssl.SSLContext rawCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        rawCtx.init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);
        SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, engineCtx);

        // Pre-write some data into applicationInBuf
        ByteBuffer appBuf = engineCtx.applicationInBuf;
        appBuf.put("hello".getBytes());
        Assertions.assertTrue(appBuf.position() > 0);

        // read should consume the buffered data first
        ByteBuffer readBuf = ByteBuffer.allocate(10);
        int n = ctx.read(readBuf);
        Assertions.assertEquals(5, n);
        readBuf.flip();
        byte[] data = new byte[readBuf.remaining()];
        readBuf.get(data);
        Assertions.assertEquals("hello", new String(data));
    }

    // ==================== createClientContext ====================
    // Full SSL handshake (doHandshake path) is covered by SslTlsIntegrationTest / Http2OverTlsIntegrationTest.
    // Here we just verify the factory creates the SSL context correctly without doing handshake.

    @Test
    public void testIsSSLAndDisabledMethods() throws Exception {
        // Create SSL-enabled context (not disabled, not doing handshake)
        javax.net.ssl.SSLContext rawCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        rawCtx.init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);
        SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
        ChannelSSLContext ctx = new ChannelSSLContext(600L, clientChannel, engineCtx);

        Assertions.assertTrue(ctx.isSSL());
        Assertions.assertTrue(ctx.getWriteBufferSize() > 0);
        Assertions.assertNull(ctx.getHandShakedApplicationProtocol());
    }

    // ==================== createClientContext ====================

    @Test
    public void testCreateClientContextFailsOnPlainConnection() throws Exception {
        // Close the server side so channelRead returns -1 immediately (connection closed)
        if (serverSide != null) {
            serverSide.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
        Thread.sleep(50);

        Assertions.assertThrows(IOException.class,
                () -> ChannelSSLContext.createClientContext(999L, clientChannel, null));
    }

    // ==================== Full SSL handshake + read/write/flush ====================

    @Test
    public void testFullSSLHandshakeAndDataTransfer() throws Exception {
        // Start an HTTPServer with SSL on a new port
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> {
                    response.setContentType("text/plain");
                    response.body("Hello SSL".getBytes());
                })
                .start();
        try {
            Thread.sleep(200); // wait for server to start

            // Connect a raw SocketChannel to the SSL server
            SocketChannel sslChannel = SocketChannel.open();
            sslChannel.connect(new InetSocketAddress("127.0.0.1", sslPort));
            sslChannel.configureBlocking(false);

            // Perform full SSL handshake via createClientContext
            ChannelSSLContext sslCtx = ChannelSSLContext.createClientContext(100L, sslChannel, null);
            Assertions.assertNotNull(sslCtx);
            Assertions.assertTrue(sslCtx.isSSL());
            Assertions.assertFalse(sslCtx.sslEngineCtx.isDisabled());

            // Write an HTTP GET request (SSL encrypted)
            byte[] httpReq = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
            ByteBuffer writeBuf = ByteBuffer.wrap(httpReq);
            int writeResult = sslCtx.write(writeBuf);
            Assertions.assertEquals(0, writeResult); // SSL write always returns 0
            sslCtx.flush(); // flush encrypted data to channel

            // Wait for response
            Thread.sleep(200);

            // Read the response (SSL decrypted)
            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int totalRead = 0;
            int retries = 0;
            while (totalRead == 0 && retries < 10) {
                int n = sslCtx.read(readBuf);
                if (n > 0) {
                    totalRead += n;
                } else if (n == -1) {
                    break;
                } else {
                    retries++;
                    Thread.sleep(100);
                }
            }
            Assertions.assertTrue(totalRead > 0, "Should read SSL-decrypted HTTP response");
            readBuf.flip();
            String response = new String(readBuf.array(), readBuf.position(), readBuf.remaining());
            Assertions.assertTrue(response.contains("Hello SSL"), "Response should contain body data");
            sslChannel.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== SSL write/read with multiple chunks ====================

    @Test
    public void testSslWriteMultipleChunks() throws Exception {
        // Use the same pattern as testFullSSLHandshakeAndDataTransfer
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> {
                    response.setContentType("text/plain");
                    response.body("OK".getBytes());
                })
                .start();
        try {
            Thread.sleep(200);
            SocketChannel sslChannel = SocketChannel.open();
            sslChannel.connect(new InetSocketAddress("127.0.0.1", sslPort));
            sslChannel.configureBlocking(false);

            ChannelSSLContext sslCtx = ChannelSSLContext.createClientContext(200L, sslChannel, null);
            Assertions.assertTrue(sslCtx.isSSL());

            // Write two separate chunks
            sslCtx.write(ByteBuffer.wrap("GET ".getBytes()));
            sslCtx.flush();
            sslCtx.write(ByteBuffer.wrap("/ HTTP/1.1\r\n".getBytes()));
            sslCtx.write(ByteBuffer.wrap("Host: localhost\r\n\r\n".getBytes()));
            sslCtx.flush();

            // Read response
            Thread.sleep(300);
            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int total = 0, retries = 0;
            while (total == 0 && retries < 15) {
                int n = sslCtx.read(readBuf);
                if (n > 0) total += n;
                else if (n == -1) break;
                else { retries++; Thread.sleep(100); }
            }
            Assertions.assertTrue(total > 0, "Should read response");
            sslChannel.close();
        } finally {
            sslServer.shutdown();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
