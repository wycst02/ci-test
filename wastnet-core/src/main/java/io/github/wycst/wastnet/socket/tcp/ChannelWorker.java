package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketConf;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IO event processing thread, each Worker maintains an independent Selector.
 */
final class ChannelWorker extends Thread {
    static final Log LOG = LogFactory.getLog(ChannelWorker.class);
    static ThreadLocal<Thread> workerThreadTl = new ThreadLocal<Thread>();

    final String workId;
    final Selector selector;
    final ByteBuffer workerBuffer;
    volatile boolean registering;
    final AtomicInteger connectionCount = new AtomicInteger(0);
    /** Per-worker single-thread scheduled executor for idle detection, lazily initialized */
    volatile ScheduledExecutorService scheduledExecutorService;

    final NioEngine<?> engine;

    public ChannelWorker(NioEngine<?> engine) throws IOException {
        this.engine = engine;
        this.workId = Utils.hex();
        this.selector = Selector.open();
        this.workerBuffer = ByteBuffer.allocate(engine.nioConfig.getReadBufferSize());
    }

    static boolean isSyncWorkerThread() {
        return workerThreadTl.get() == Thread.currentThread();
    }

    /**
     * Get application protocols configured on the engine.
     */
    String[] getApplicationProtocols() {
        return engine.nioConfig.getApplicationProtocols();
    }

    /**
     * Lazily create a single-thread scheduled executor for this worker.
     */
    ScheduledExecutorService getScheduledExecutorService() {
        if (scheduledExecutorService == null) {
            synchronized (this) {
                if (scheduledExecutorService == null) {
                    scheduledExecutorService = Executors.newScheduledThreadPool(1);
                }
            }
        }
        return scheduledExecutorService;
    }

    /**
     * Submit a task to a background executor to avoid blocking the worker thread.
     * Falls back to the shared executor service if runner executor is not configured.
     */
    void runAsync(Runnable runnable) {
        ExecutorService executor = engine.runnerExecutor != null ? engine.runnerExecutor : engine.executorService;
        executor.execute(runnable);
    }

    public void register(SocketChannel client, ChannelRunner channelRunner) throws ClosedChannelException {
        registering = true;
        selector.wakeup();
        try {
            SelectionKey selectionKey = client.register(selector, SelectionKey.OP_READ, channelRunner);
            channelRunner.setReadKey(selectionKey);
            connectionCount.incrementAndGet();
        } finally {
            registering = false;
            synchronized (this) {
                notify();
            }
        }
    }

    public int getConnectionCount() {
        return connectionCount.get();
    }

    public void decrementConnectionCount() {
        connectionCount.decrementAndGet();
    }

    public void wakeup() {
        selector.wakeup();
    }

    void handleSelectedKeys(Set<SelectionKey> selectedKeys) throws IOException {
        while (!selectedKeys.isEmpty()) {
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                ChannelRunner runner = (ChannelRunner) key.attachment();
                try {
                    if (!key.isValid()) {
                        runner.close();
                        continue;
                    }
                    // Handle OP_WRITE — notify blocked writer
                    if (key.isWritable()) {
                        runner.ctx.wakeupWrite();
                        continue;
                    }
                    if (!runner.isRunFlag()) {
                        runner.runFlag = true;
                        // Dynamic decision: sync for fast connections, async for slow connections
                        if (runner.predictSync() || engine.nioConfig.isSyncRunner()) {
                            runner.run0();
                        } else {
                            engine.runnerExecutor.execute(runner);
                        }
                    } else {
                        runner.wakeup();
                    }
                } catch (Throwable throwable) {
                    if (!key.isValid()) {
                        LOG.debug("channel key is invalid(cancel)");
                    }
                    if (engine.nioConfig.isPrintReadErrorLog()) {
                        throwable.printStackTrace();
                    }
                    runner.close();
                }
            }
        }
    }

    void handleRead() throws Throwable {
        // Signal that worker thread has entered its event loop
        CountDownLatch latch = engine.startLatch;
        if (latch != null) latch.countDown();

        int selectZeroCount = 0;
        final Set<SelectionKey> selectedKeys = selector.selectedKeys();
        final long selectTimeoutMs = SocketConf.SELECT_TIMEOUT_MS;
        final long selectEmptyCount = SocketConf.SELECT_EMPTY_COUNT;
        while (engine.engineRunFlag) {
            int num = selector.select(selectTimeoutMs);
            if (num == 0) {
                if (++selectZeroCount < selectEmptyCount) {
                    continue;
                }
                selectZeroCount = 0;
                num = selector.select();
                if (registering) {
                    synchronized (this) {
                        wait();
                    }
                }
                if (num == 0) {
                    continue;
                }
            }
            handleSelectedKeys(selectedKeys);
        }
        selector.close();
    }

    @Override
    public void run() {
        try {
            workerThreadTl.set(Thread.currentThread());
            handleRead();
        } catch (Throwable ignored) {
        } finally {
            workerThreadTl.remove();
            if (scheduledExecutorService != null) {
                Utils.shutdownExecutorService(scheduledExecutorService);
            }
        }
    }
}
