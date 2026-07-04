package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.concurrent.TimeUnit;

/**
 * Global singleton
 */
public abstract class IdleStateHandler {

    public enum IdleType {
        Read, Write
    }

    private final long readerIdleTimeNanos;
    private final long writerIdleTimeNanos;

    public IdleStateHandler(long readerIdleTime, long writerIdleTime, TimeUnit unit) {
        this.readerIdleTimeNanos = readerIdleTime > 0 ? unit.toNanos(readerIdleTime) : 0L;
        this.writerIdleTimeNanos = writerIdleTime > 0 ? unit.toNanos(writerIdleTime) : 0L;
    }

    public long getReaderIdleTimeNanos() {
        return readerIdleTimeNanos;
    }

    public long getWriterIdleTimeNanos() {
        return writerIdleTimeNanos;
    }

    /**
     * Triggered when a channel has been idle for the configured time.
     *
     * <p><b>Threading note:</b> This method runs on the worker's single-thread
     * scheduled executor. Implementations must be lightweight (e.g. send a
     * heartbeat, close the channel, or update a counter). Heavy work such as
     * database queries or blocking HTTP calls will stall idle detection for
     * all connections bound to the same worker. Offload heavy operations to
     * a dedicated business thread pool if necessary.
     *
     * @param ctx                     the channel context
     * @param idleType                Read or Write idle type
     * @param triggerTotalCount       total number of idle triggers so far
     * @param triggerConsecutiveCount consecutive idle triggers since last activity
     * @throws Throwable if an error occurs during handling
     */
    public abstract void onIdleTriggered(ChannelContext ctx, IdleType idleType, long triggerTotalCount, long triggerConsecutiveCount) throws Throwable;

}
