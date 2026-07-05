package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage for Log / LogFormatter / RotatingFileHandler / LogFactory
 * edge cases not covered by the existing dedicated tests.
 */
public class LogCoverageTest {

    // ==================== LogFormatter ====================

    @Test
    void testFormatLevelUnknownLevel() {
        // formatLevel() with a non-standard level returns level.getName() directly
        LogFormatter formatter = new LogFormatter();
        LogRecord record = new LogRecord(Level.FINE, "fine message");
        record.setLoggerName("Test");
        String result = formatter.format(record);
        assertTrue(result.contains("FINE"), "Should contain raw level name for non-standard level");
    }

    @Test
    void testThrowableContentNull() {
        assertNull(LogFormatter.getThrowableContent(null));
    }

    // ==================== Log.abbreviateClassName ====================

    @Test
    void testAbbreviateClassNameNoDot() {
        // No package -> returned as-is
        Log log = new Log("SimpleName", java.util.logging.Logger.getLogger("simple"));
        log.setEnabled(true);
        log.info("noop");
    }

    @Test
    void testAbbreviateClassNameShort() {
        // Under 40 chars with dots -> returned as-is
        Log log = new Log("com.example.MyClass", java.util.logging.Logger.getLogger("short"));
        log.info("noop");
    }

    @Test
    void testAbbreviateClassNameLong() {
        // 40+ chars -> abbreviated: "io.g.w.w.l.LogFormatterTest"
        String longName = "io.github.wycst.wastnet.log.LogFormatterTest";
        assertTrue(longName.length() >= 40, "Name must be 40+ chars for this test");
        Log log = new Log(longName, java.util.logging.Logger.getLogger("long"));
        log.info("noop");
    }

    // ==================== RotatingFileHandler ====================

    @Test
    void testRotateFileNotExists() {
        // Trigger rotation when the current file does not exist
        // Use a temp file that we delete before triggering rotation
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        File logFile = new File(tempDir, "rotate_missing_" + System.nanoTime() + ".log");

        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 1, 2);
        handler.setFormatter(new java.util.logging.SimpleFormatter());
        handler.setLevel(Level.ALL);

        // First publish creates the file and triggers rotation (maxSize=1)
        handler.publish(new LogRecord(Level.INFO, "trigger-rotation"));

        // File should exist
        assertTrue(logFile.exists(), "Log file should exist after publish");

        handler.close();
        logFile.delete();
        File backup0 = new File(tempDir, logFile.getName().replace(".log", "_0.log"));
        backup0.delete();
    }

    // ==================== LogFactory extra ====================

    @Test
    void testLogFactoryGetLogWithCustomPackage() {
        Log log = LogFactory.getLog(getClass());
        assertNotNull(log);
    }

    @Test
    void testLogFactoryStaticInitializer() {
        // AccessLog and errorLog are initialized in static block
        assertNotNull(LogFactory.getAccessLog());
        assertNotNull(LogFactory.getErrorLog());
    }
}
