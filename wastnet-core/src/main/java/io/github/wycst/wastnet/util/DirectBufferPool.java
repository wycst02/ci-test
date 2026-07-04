package io.github.wycst.wastnet.util;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direct ByteBuffer Memory Pool
 *
 * <p>Design specs:
 * <ul>
 *   <li>Pool size: 1024 x 8KB = 8MB total</li>
 *   <li>All buffers pre-allocated at init</li>
 *   <li>Idle buffers can be released back to pool</li>
 *   <li>Fallback: allocate heap buffer when exceeding pool limit</li>
 * </ul>
 *
 * @author wangyc
 */
public final class DirectBufferPool {

    /**
     * Pool size limit (8KB buffer count)
     */
    private static final int POOL_SIZE = 1024;

    /**
     * Buffer size
     */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Buffer pool
     */
    private static final PooledBuffer[] POOL = new PooledBuffer[POOL_SIZE];

    /**
     * Allocated count (boundary: POOL[0..count-1] are allocated)
     */
    private static final AtomicInteger count = new AtomicInteger(0);

    static {
        for (int i = 0; i < POOL_SIZE; i++) {
            POOL[i] = new PooledBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE), i);
        }
    }

    /**
     * Pooled Buffer Wrapper
     */
    public static final class PooledBuffer {
        private final ByteBuffer buffer;
        /**
         * Current position in pool array
         */
        private int index;

        PooledBuffer(ByteBuffer buffer, int index) {
            this.buffer = buffer;
            this.index = index;
        }

        public ByteBuffer get() {
            return buffer;
        }

        /**
         * Release this buffer back to pool
         */
        public void release() {
            DirectBufferPool.release(this);
        }
    }

    /**
     * Acquire a pooled buffer from pool (lock-free)
     */
    public static PooledBuffer acquireBuffer() {
        int c = count.getAndIncrement();
        if (c >= POOL_SIZE) {
            count.decrementAndGet();
            return new PooledBuffer(ByteBuffer.allocate(BUFFER_SIZE), -1);
        }
        return POOL[c];
    }

    /**
     * Release buffer back to pool (lock-free)
     */
    static void release(PooledBuffer pb) {
        try {
            if (pb.index == -1) {
                return;
            }
            while (true) {
                int c = count.get(), dest = c - 1;
                if (count.compareAndSet(c, dest)) {
                    int i = pb.index;
                    if (i == dest) {
                        return;
                    }
                    PooledBuffer destPb = POOL[dest];
                    pb.index = dest;
                    destPb.index = i;

                    POOL[dest] = pb;
                    POOL[i] = destPb;
                    return;
                }
            }
        } finally {
            pb.buffer.clear();
        }
    }
}