package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers idle state detection for connections that have been established
 * but never sent any data (zero data transmission since connection opened).
 *
 * @Date 2024/1/25 15:01
 * @Created by wangyc
 */
public final class IdleStateHandlerTrigger {

    final IdleStateHandler idleStateHandler;
    final ChannelContext ctx;
    final long readerIdleTimeNanos;
    final long writerIdleTimeNanos;

    long lastReadNanos;
    long idleReadTriggerCnt;
    long idleReadTriggerConsecutiveCnt;
    long lastWriteNanos;
    long idleWriteTriggerCnt;
    long idleWriteTriggerConsecutiveCnt;

    ScheduleWriteTask scheduleWriteTask;
    ScheduleReadTask scheduleReadTask;
    ScheduledFuture<?> readIdleFuture;
    ScheduledFuture<?> writeIdleFuture;
    /** Flag to prevent rescheduling idle tasks after the connection is released */
    volatile boolean released;
    static final long MIN_NANOS = 1000000000L;

    public IdleStateHandlerTrigger(IdleStateHandler idleStateHandler, ChannelContext ctx) {
        this.idleStateHandler = idleStateHandler;
        this.ctx = ctx;
        this.readerIdleTimeNanos = idleStateHandler.getReaderIdleTimeNanos();
        this.writerIdleTimeNanos = idleStateHandler.getWriterIdleTimeNanos();
        if (readerIdleTimeNanos >= MIN_NANOS) {
            this.scheduleReadTask = new ScheduleReadTask();
            scheduleReadTask(readerIdleTimeNanos);
            onReadTriggered();
        }
        if (writerIdleTimeNanos >= MIN_NANOS) {
            this.scheduleWriteTask = new ScheduleWriteTask();
            scheduleWriteTask(writerIdleTimeNanos);
            onWriteTriggered();
        }
    }

    public void release() {
        // Mark as released first to stop any in-flight task from rescheduling
        released = true;
        if (readIdleFuture != null) {
            readIdleFuture.cancel(false);
        }
        if (writeIdleFuture != null) {
            writeIdleFuture.cancel(false);
        }
    }

    class ScheduleWriteTask implements Runnable {
        @Override
        public void run() {
            // Skip if the connection has been released to avoid ghost tasks
            if (released) return;
            long useNanos = System.nanoTime() - lastWriteNanos;
            long rem = writerIdleTimeNanos - useNanos;
            writeIdleFuture.cancel(false);
            if (rem > MIN_NANOS) {
                scheduleWriteTask(rem);
                return;
            }
            try {
                increaseTriggerWriteCount();
                idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Write, idleWriteTriggerCnt, idleWriteTriggerConsecutiveCnt);
            } catch (Throwable ignored) {
            } finally {
                scheduleWriteTask(writerIdleTimeNanos);
            }
        }
    }

    class ScheduleReadTask implements Runnable {
        @Override
        public void run() {
            // Skip if the connection has been released to avoid ghost tasks
            if (released) return;
            long useNanos = System.nanoTime() - lastReadNanos;
            long rem = readerIdleTimeNanos - useNanos;
            readIdleFuture.cancel(false);
            if (rem > MIN_NANOS) {
                scheduleReadTask(rem);
                return;
            }
            try {
                increaseTriggerReadCount();
                idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Read, idleReadTriggerCnt, idleReadTriggerConsecutiveCnt);
            } catch (Throwable ignored) {
            } finally {
                scheduleReadTask(readerIdleTimeNanos);
            }
        }
    }

    private void scheduleWriteTask(long timeNanos) {
        // Guard against reschedule after the connection is released
        if (released) return;
        writeIdleFuture = ctx.schedule(scheduleWriteTask, timeNanos, TimeUnit.NANOSECONDS);
    }

    private void increaseTriggerWriteCount() {
        ++idleWriteTriggerCnt;
        ++idleWriteTriggerConsecutiveCnt;
        if (idleWriteTriggerCnt <= 0) {
            idleWriteTriggerCnt = 1;
        }
        if (idleWriteTriggerConsecutiveCnt <= 0) {
            idleWriteTriggerConsecutiveCnt = 1;
        }
    }

    private void scheduleReadTask(long timeNanos) {
        // Guard against reschedule after the connection is released
        if (released) return;
        readIdleFuture = ctx.schedule(scheduleReadTask, timeNanos, TimeUnit.NANOSECONDS);
    }

    private void increaseTriggerReadCount() {
        ++idleReadTriggerCnt;
        ++idleReadTriggerConsecutiveCnt;
        if (idleReadTriggerCnt <= 0) {
            idleReadTriggerCnt = 1;
        }
        if (idleReadTriggerConsecutiveCnt <= 0) {
            idleReadTriggerConsecutiveCnt = 1;
        }
    }

    public void onReadTriggered() {
        lastReadNanos = System.nanoTime();
        this.idleReadTriggerConsecutiveCnt = 0;
    }

    public void onWriteTriggered() {
        lastWriteNanos = System.nanoTime();
        this.idleWriteTriggerConsecutiveCnt = 0;
    }
}
