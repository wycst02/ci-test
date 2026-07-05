package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class HttpProxyCoverageTest {

    static ChannelContext createCtx() throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        return new ChannelContext(ch, 4096);
    }

    @Test
    public void testHttpProxyWorkerIsUpgrade101() throws Exception {
        ByteBuffer empty = ByteBuffer.wrap(new byte[20]);
        assertFalse(HttpProxyWorker.isUpgrade101(empty));
        ByteBuffer upgrade = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols".getBytes());
        assertTrue(HttpProxyWorker.isUpgrade101(upgrade));
    }

    @Test
    public void testHttpProxyWorkerShutdown() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        worker.shutdown();
    }

    @Test
    public void testHttpProxyWorkerCleanupConnection() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        worker.cleanupConnection(conn);
    }

    @Test
    public void testHttpProxyWorkerCleanupConnectionIdempotent() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        worker.cleanupConnection(conn);
        // Second call should be no-op (connection.closed already true)
        worker.cleanupConnection(conn);
    }

    @Test
    public void testHttpProxyWorkerClearConnections() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        worker.clearConnections("test-route");
    }

    @Test
    public void testH2H1ProxyAdapterConstructor() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertNotNull(adapter);
    }

    @Test
    public void testH2H1ProxyAdapterOnData() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        try { adapter.onData(ByteBuffer.allocate(10), ctx, true, conn); } catch (Exception ignored) {}
    }

    @Test
    public void testHttpProxyConnectionClose() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = createCtx();
        ChannelContext targetCtx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        conn.close();
    }

    @Test
    public void testH2H1ProxyAdapterReceiveResponse() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        adapter.receiveResponse(ctx);
    }

    @Test
    public void testH2H1ProxyAdapterTryAcquire() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertFalse(adapter.tryAcquire());
    }

    // ==================== resolveAdapter: H2→H2 throws ====================

    @Test
    public void testResolveAdapterH2ToH2Throws() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = mock(ChannelContext.class);
        ChannelContext targetCtx = mock(ChannelContext.class);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(clientCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(targetCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(clientCtx.channel()).thenReturn(ch);
        when(targetCtx.channel()).thenReturn(ch);
        assertThrows(UnsupportedOperationException.class,
                () -> new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker));
    }

    // ==================== resolveAdapter: H1→H2 returns PASSTHROUGH ====================

    @Test
    public void testResolveAdapterH1ToH2() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = mock(ChannelContext.class);
        ChannelContext targetCtx = mock(ChannelContext.class);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(clientCtx.getHandShakedApplicationProtocol()).thenReturn(null);
        when(targetCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(clientCtx.channel()).thenReturn(ch);
        when(targetCtx.channel()).thenReturn(ch);
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        assertFalse(conn.adapter instanceof H2H1ProxyAdapter);
    }

    // ==================== cleanupConnection with timeoutFuture ====================

    @Test
    public void testCleanupConnectionCancelsTimeout() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.timeoutFuture = HttpProxyWorkerManager.scheduleTimeout(() -> {}, 5000);
        assertNotNull(conn.timeoutFuture);
        worker.cleanupConnection(conn);
        // timeoutFuture cancelled and set to null
        assertNull(conn.timeoutFuture);
    }

    // ==================== cleanupConnection with clientKey ====================

    @Test
    public void testCleanupConnectionClosesClientChannel() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        // Trigger registerClientRead through upgrade detection
        conn.isUpgrade = true;
        conn.clientH2 = false;
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols\r\n".getBytes());
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
        worker.cleanupConnection(conn);
        assertTrue(conn.closed);
    }

    // ==================== clearConnections with matching route ====================

    @Test
    public void testClearConnectionsMatching() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        // Connection is registered in HttpProxyRoute, not constructor; add manually
        HttpProxyConnection conn = new HttpProxyConnection(1L, "myRoute", ctx, ctx, worker);
        worker.connections.put(1L, conn);
        worker.clearConnections("myRoute");
        assertTrue(conn.closed);
    }

    // ==================== registerClientRead idempotent ====================

    @Test
    public void testRegisterClientReadTwice() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.isUpgrade = true;
        conn.clientH2 = false;
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols\r\n".getBytes());
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
        // Second call should be no-op
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
    }
}
