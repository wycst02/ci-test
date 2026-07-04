package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.socket.conf.SocketConf;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;

/**
 * High-performance TCP server implementation based on NIO selector.
 * <p>
 * Handles accepting incoming connections and dispatching them to workers.
 *
 * @Date 2024/1/14 19:27
 * @Created by wangyc
 */
public class TCPServer extends NioEngine<TCPServer> {

    protected boolean localOnly;
    volatile Selector selector;
    ServerSocketChannel serverChannel;

    public TCPServer(int port) {
        this(port, new NioConfig());
    }

    public TCPServer(int port, NioConfig nioConfig) {
        super(port, nioConfig);
    }

    // ==================== Server-specific methods ====================

    /**
     * Set whether the server only accepts local connections (bound to 127.0.0.1)
     */
    public TCPServer localOnly(boolean localOnly) {
        this.localOnly = localOnly;
        return this;
    }

    /**
     * Set connection filter for accept-level rejection.
     */
    public TCPServer connectionFilter(ConnectionFilter connectionFilter) {
        nioConfig.setConnectionFilter(connectionFilter);
        return this;
    }

    // ==================== Worker management ====================

    ChannelWorker[] workers() throws IOException {
        int workNum = nioConfig.getWorkerNum();
        ChannelWorker[] selectorWorks = new ChannelWorker[workNum];
        for (int i = 0; i < workNum; ++i) {
            selectorWorks[i] = new ChannelWorker(this);
        }
        return selectorWorks;
    }

    ChannelWorker nextWorker(int clientCnt, ChannelWorker[] workers) {
        if (SocketConf.useLoadBalanceLeastConnections()) {
            int minIdx = 0;
            int minConn = workers[0].getConnectionCount();
            for (int i = 1; i < workers.length; i++) {
                int c = workers[i].getConnectionCount();
                if (c < minConn) {
                    minConn = c;
                    minIdx = i;
                    if (minConn == 0) {
                        break;
                    }
                }
            }
            return workers[minIdx];
        } else {
            return workers[clientCnt & (workers.length - 1)];
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Start the TCP server
     */
    public synchronized TCPServer start() {
        checkServerAvailable();
        try {
            engineRunFlag = true;
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            ServerSocket serverSocket = serverChannel.socket();
            serverSocket.setReuseAddress(true);
            serverSocket.setReceiveBufferSize(65536);
            serverSocket.bind(localOnly ? new InetSocketAddress("127.0.0.1", port) : new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            this.initSslContext();
            final ChannelWorker[] ioWorkers = workers();
            // Create latch before starting threads: 1 AcceptDispatcher + N workers
            startLatch = new CountDownLatch(1 + ioWorkers.length);
            new AcceptDispatcher(this, ioWorkers).start();
            for (int i = 0; i < ioWorkers.length; i++) {
                new Thread(ioWorkers[i], "worker-" + i).start();
            }
            // Wait for all threads to enter their event loops before calling onStarted()
            startLatch.await();
            startLatch = null;
        } catch (Throwable e) {
            stop();
            if(e instanceof BindException) {
                throw new SocketException("Failed to bind port " + port + ": " + e.getMessage(), e);
            }
            throw e instanceof RuntimeException ? (RuntimeException) e : new SocketException(e.getMessage(), e);
        }
        onStarted();
        return this;
    }

    /**
     * Callback after server has started. Subclasses may override for custom banner.
     */
    protected void onStarted() {
        LOG.info("server startup on {}", localOnly ? "127.0.0.1:" + port : "port " + port);
    }

    /**
     * Create connection runner based on SSL configuration
     */
    ChannelRunner createConnectionRunner(ChannelWorker worker, SocketChannel socketChannel) throws IOException {
        if (ssl) {
            return new ChannelSSLRunner(worker, socketChannel, nioConfig, getSSLContext(), sslCipherSuites);
        } else {
            return new ChannelRunner(worker, socketChannel, nioConfig);
        }
    }

    /**
     * Stop the service (can be restarted)
     */
    @Override
    public synchronized void stop() {
        try {
            if (engineRunFlag) {
                engineRunFlag = false;
                selector.wakeup();
                selector.close();
                serverChannel.close();
                nioConfig.clear();
                onStopped();
            }
        } catch (Throwable e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new SocketException(e.getMessage(), e);
        }
    }

    /**
     * Callback after server has stopped. Subclasses may override for custom output.
     */
    protected void onStopped() {
        LOG.info("server is stopped");
    }

    /**
     * Restart the service
     */
    public synchronized void restart() {
        LOG.info("restart ...");
        stop();
        start();
    }
}
