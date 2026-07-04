package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

/**
 * Tests for {@link TCPServer}.
 */
public class TCPServerTest {

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18080;
        }
    }

    // ==================== Constructors & setters ====================

    @Test
    public void testConstructorWithPort() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertEquals(8080, server.getPort());
        server.shutdown();
    }

    @Test
    public void testConstructorWithPortAndConfig() {
        TCPServer server = new TCPServer(9090, new NioConfig());
        Assertions.assertEquals(9090, server.getPort());
        server.shutdown();
    }

    @Test
    public void testLocalOnly() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertSame(server, server.localOnly(true));
        server.shutdown();
    }

    @Test
    public void testConnectionFilter() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertSame(server, server.connectionFilter(ctx -> true));
        server.shutdown();
    }

    // ==================== Workers ====================

    @Test
    public void testWorkers() throws Exception {
        TCPServer server = new TCPServer(18080);
        try {
            ChannelWorker[] workers = server.workers();
            Assertions.assertTrue(workers.length >= 2);
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testNextWorkerRoundRobin() throws Exception {
        TCPServer server = new TCPServer(18080);
        try {
            ChannelWorker[] workers = server.workers();
            ChannelWorker w0 = server.nextWorker(0, workers);
            ChannelWorker w1 = server.nextWorker(1, workers);
            Assertions.assertNotNull(w0);
            Assertions.assertNotNull(w1);
            Assertions.assertNotSame(w0, w1);
        } finally {
            server.shutdown();
        }
    }

    // ==================== Start / Stop / Restart ====================

    @Test
    public void testStartAndStop() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            TCPServer result = server.start();
            Assertions.assertSame(server, result);
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testStartBindError() throws Exception {
        // Bind to a port first, then try to create a server on the same port
        java.net.ServerSocket occupied = new java.net.ServerSocket(findFreePort());
        int occupiedPort = occupied.getLocalPort();
        TCPServer server = new TCPServer(occupiedPort);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            Assertions.assertThrows(SocketException.class, server::start);
        } finally {
            occupied.close();
            server.shutdown();
        }
    }

    @Test
    public void testStopWhenNotStarted() {
        TCPServer server = new TCPServer(8080);
        server.stop(); // should not throw
        server.shutdown();
    }

    @Test
    public void testRestart() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            server.restart(); // stop + start
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testStartWithLocalOnly() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.localOnly(true);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.stop();
        }
    }

    // ==================== createConnectionRunner ====================

    @Test
    public void testCreateConnectionRunnerNonSSL() throws Exception {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            ChannelWorker worker = server.workers()[0];
            // Non-SSL → creates plain ChannelRunner
            java.net.ServerSocket ss = new java.net.ServerSocket(port + 1);
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open();
            ch.connect(new java.net.InetSocketAddress("127.0.0.1", ss.getLocalPort()));
            ChannelRunner runner = server.createConnectionRunner(worker, ch);
            Assertions.assertNotNull(runner);
            Assertions.assertFalse(runner instanceof ChannelSSLRunner);
            ch.close();
            ss.close();
        } finally {
            server.stop();
        }
    }

    @Test
    public void testCreateConnectionRunnerWithSSL() throws Exception {
        int port = findFreePort();
        // Configure SSL
        javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        TCPServer server = new TCPServer(port)
                .ssl(true)
                .sslContext(sslCtx);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            ChannelWorker worker = server.workers()[0];
            // SSL → creates ChannelSSLRunner
            java.net.ServerSocket ss = new java.net.ServerSocket(port + 2);
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open();
            ch.connect(new java.net.InetSocketAddress("127.0.0.1", ss.getLocalPort()));
            ChannelRunner runner = server.createConnectionRunner(worker, ch);
            Assertions.assertNotNull(runner);
            Assertions.assertTrue(runner instanceof ChannelSSLRunner);
            ch.close();
            ss.close();
        } finally {
            server.stop();
        }
    }

    // ==================== nextWorker load balance ====================

    private static final Object UNSAFE;
    static {
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void setFinalBoolean(Class<?> clazz, String fieldName, boolean value) throws Exception {
        java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        long offset = (long) UNSAFE.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(UNSAFE, f);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(UNSAFE, f);
        boolean original = (boolean) UNSAFE.getClass().getMethod("getBoolean", Object.class, long.class).invoke(UNSAFE, base, offset);
        UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, base, offset, value);
    }

    @Test
    public void testNextWorkerLoadBalance() throws Exception {
        // Temporarily enable load balance via Unsafe
        java.lang.reflect.Field lbField = io.github.wycst.wastnet.socket.conf.SocketConf.class.getDeclaredField("USE_LEAST_CONNECTIONS");
        lbField.setAccessible(true);
        long offset = (long) UNSAFE.getClass().getMethod("staticFieldOffset", java.lang.reflect.Field.class).invoke(UNSAFE, lbField);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(UNSAFE, lbField);
        boolean orig = (boolean) UNSAFE.getClass().getMethod("getBoolean", Object.class, long.class).invoke(UNSAFE, base, offset);
        try {
            UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, base, offset, true);

            int port = findFreePort();
            TCPServer server = new TCPServer(port);
            server.config().setChannelHandler(new ChannelHandler<byte[]>() {
                @Override
                public void onHandle(ChannelContext ctx, byte[] message) {}
            });
            try {
                server.start();
                ChannelWorker[] workers = server.workers();

                // Decrement one worker more than others to create imbalance
                workers[0].decrementConnectionCount();
                workers[0].decrementConnectionCount();

                // nextWorker should select the one with least connections
                ChannelWorker selected = server.nextWorker(0, workers);
                Assertions.assertNotNull(selected);
            } finally {
                server.stop();
            }
        } finally {
            UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, base, offset, orig);
        }
    }
}
