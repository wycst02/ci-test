package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Creates a Log instance backed by a Logger with no parent handlers,
 * to avoid printing log output during tests.
 */
class LogTestHelper {
    static Log newSilentLog(String name) {
        Logger raw = Logger.getLogger(name);
        raw.setUseParentHandlers(false);
        raw.setLevel(Level.ALL);
        return new Log(name, raw);
    }
}

/**
 * Unit tests for {@link Log} class.
 */
public class LogTest {

    @Test
    public void testTraceEnabledWhenNotCalled() {
        Log log = LogTestHelper.newSilentLog("test.trace.off");
        log.setEnabled(false);
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.trace("should not appear");
        verify(mockHandler, never()).publish(any(LogRecord.class));
    }

    @Test
    public void testTraceEnabledWhenTrue() {
        Log log = LogTestHelper.newSilentLog("test.trace.on");
        log.setEnabled(true);
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.trace("should appear");
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
    }

    @Test
    public void testDebugLogsViaConfig() {
        Log log = LogTestHelper.newSilentLog("test.debug");
        log.setEnabled(true);
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.debug("debug message: {}", "test");
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
    }

    @Test
    public void testInfoLogs() {
        Log log = LogTestHelper.newSilentLog("test.info");
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.info("info message");
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
    }

    @Test
    public void testWarnLogs() {
        Log log = LogTestHelper.newSilentLog("test.warn");
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.warn("warn message");
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
    }

    @Test
    public void testErrorLogs() {
        Log log = LogTestHelper.newSilentLog("test.error");
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.error("error message");
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
    }

    @Test
    public void testErrorWithThrowable() {
        java.util.logging.Logger rawLogger = java.util.logging.Logger.getLogger("test.error.throw");
        rawLogger.setUseParentHandlers(false);
        rawLogger.setLevel(java.util.logging.Level.ALL);
        Log log = new Log("throw.test", rawLogger);
        Handler mockHandler = mock(Handler.class);
        log.addHandler(mockHandler);
        log.error("error", new RuntimeException("cause"));
        verify(mockHandler, atLeastOnce()).publish(any(LogRecord.class));
        // Verify the thrown exception is attached to the LogRecord
        verify(mockHandler).publish(argThat(record ->
                record.getThrown() != null && "cause".equals(record.getThrown().getMessage())));
    }

    @Test
    public void testSetEnabledAndIsEnabled() {
        Log log = LogFactory.getLog(LogTest.class);
        log.setEnabled(true);
        Assertions.assertTrue(log.isEnabled());
        log.setEnabled(false);
        Assertions.assertFalse(log.isEnabled());
    }

    @Test
    public void testCallerInfoEnabledDoesNotThrow() {
        Log log = LogFactory.getLog(LogTest.class);
        Assertions.assertSame(log, log.callerInfoEnabled());
    }

    @Test
    public void testSetLevelDoesNotThrow() {
        Log log = LogFactory.getLog(LogTest.class);
        Assertions.assertDoesNotThrow(() -> log.setLevel(java.util.logging.Level.FINE));
    }

    // ==================== LogFactory ====================

    @Test
    public void testLogFactoryGetLogNotNull() {
        Log log = LogFactory.getLog(LogTest.class);
        Assertions.assertNotNull(log);
    }

    @Test
    public void testLogFactoryGetLogReturnsSameInstance() {
        Log log1 = LogFactory.getLog(String.class);
        Log log2 = LogFactory.getLog(String.class);
        Assertions.assertSame(log1, log2);
    }

    @Test
    public void testLogFactoryGetAccessLogNotNull() {
        Assertions.assertNotNull(LogFactory.getAccessLog());
    }

    @Test
    public void testLogFactoryGetErrorLogNotNull() {
        Assertions.assertNotNull(LogFactory.getErrorLog());
    }

    @Test
    public void testLogFactoryNewPacketLogReturnsDisabledLog() {
        Log packetLog = LogFactory.newPacketLog("test-port");
        Assertions.assertNotNull(packetLog);
        Assertions.assertFalse(packetLog.isEnabled());
    }
}
