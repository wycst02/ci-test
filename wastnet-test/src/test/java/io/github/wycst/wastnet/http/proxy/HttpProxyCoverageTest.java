package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.*;

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
        conn.close(); // should not throw
    }

    @Test
    public void testH2H1ProxyAdapterReceiveResponse() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        adapter.receiveResponse(ctx); // no-op, should not throw
    }

    @Test
    public void testH2H1ProxyAdapterTryAcquire() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertFalse(adapter.tryAcquire()); // not disposed, returns false (busy)
    }
}
