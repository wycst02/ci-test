package io.github.wycst.wastnet.http.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Global proxy worker manager.
 * Manages workers for each target address (host:port).
 *
 * @author wangyc
 */
public class HttpProxyWorkerManager {

    static final HttpProxyWorkerManager INSTANCE = new HttpProxyWorkerManager();

    static final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "proxy-read-timeout");
            t.setDaemon(true);
            return t;
        }
    });
    static final ByteBuffer GATEWAY_TIMEOUT_RESPONSE = ByteBuffer.wrap("HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\n\r\n".getBytes());

    private final Map<String, HttpProxyWorker> workers = new ConcurrentHashMap<String, HttpProxyWorker>();

    private HttpProxyWorkerManager() {
    }

    /**
     * Get or create worker for the specified target address.
     *
     * @param hostPort target host:port
     * @return HttpProxyWorker for the target
     */
    HttpProxyWorker get(String hostPort) throws IOException {
        HttpProxyWorker worker = workers.get(hostPort);
        if (worker == null) {
            synchronized (this) {
                worker = workers.get(hostPort);
                if (worker == null) {
                    worker = new HttpProxyWorker();
                    worker.setName("proxy-worker-" + hostPort);
                    workers.put(hostPort, worker);
                    worker.start();
                }
            }
        }
        return worker;
    }

    /**
     * Remove worker from manager.
     *
     * @param hostPort target host:port
     */
    void removeWorker(String hostPort) {
        workers.remove(hostPort);
    }

    /**
     * Schedule a read timeout task.
     *
     * @param task  timeout task to execute
     * @param delay delay in milliseconds
     * @return ScheduledFuture for cancellation
     */
    static ScheduledFuture<?> scheduleTimeout(Runnable task, long delay) {
        return timeoutScheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
    }
}