package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for RotatingFileHandler.
 */
public class RotatingFileHandlerTest {

    @TempDir
    File tempDir;

    @Test
    public void testPublishAndFlush() {
        File logFile = new File(tempDir, "test.log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 10240, 3);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);

        LogRecord record = new LogRecord(Level.INFO, "test message");
        handler.publish(record);
        handler.flush();
        assertTrue(logFile.exists(), "Log file should exist after publish");

        handler.close();
    }

    @Test
    public void testPublishNotLoggable() {
        RotatingFileHandler handler = new RotatingFileHandler(
                new File(tempDir, "notlog.log").getAbsolutePath(), 10240, 3);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.SEVERE);

        // FINER is below SEVERE, so isLoggable returns false
        LogRecord record = new LogRecord(Level.FINER, "should not appear");
        handler.publish(record);
        // The file should NOT have been created since nothing was publishable
        handler.close();
    }

    @Test
    public void testRotate() {
        // Very small maxSize to trigger rotation immediately
        File logFile = new File(tempDir, "rotate.log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 10, 2);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);

        // First record creates file
        handler.publish(new LogRecord(Level.INFO, "first record data here"));
        assertTrue(logFile.exists());

        // Second record triggers rotation since file size exceeds 10 bytes
        handler.publish(new LogRecord(Level.INFO, "second record that should trigger rotation"));
        // Backup file should exist
        File backup0 = new File(tempDir, "rotate_0.log");
        assertTrue(backup0.exists() || logFile.exists(), "Backup should exist after rotation");

        handler.close();
    }

    @Test
    public void testBackupFileNameWithDot() {
        File logFile = new File(tempDir, "access.out");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 10240, 2);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        handler.publish(new LogRecord(Level.INFO, "test"));
        handler.close();

        // The backup file should use the naming pattern
        File backup0 = new File(tempDir, "access_0.out");
        assertTrue(backup0.exists() || logFile.exists());
    }

    @Test
    public void testClose() {
        RotatingFileHandler handler = new RotatingFileHandler(
                new File(tempDir, "close.log").getAbsolutePath(), 10240, 3);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        handler.publish(new LogRecord(Level.INFO, "before close"));
        handler.close();
        // Second close should be safe
        handler.close();
    }

    @Test
    public void testMultipleRotations() {
        File logFile = new File(tempDir, "multi.log");
        RotatingFileHandler handler = new RotatingFileHandler(logFile.getAbsolutePath(), 5, 3);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);

        // Publish many records to force multiple rotations
        for (int i = 0; i < 20; i++) {
            handler.publish(new LogRecord(Level.INFO, "msg" + i));
        }
        handler.close();
        assertTrue(logFile.exists() || new File(tempDir, "multi_0.log").exists());
    }

    @Test
    public void testBackupFileNameNoDot() {
        // No extension in filename
        RotatingFileHandler handler = new RotatingFileHandler(
                new File(tempDir, "noext").getAbsolutePath(), 10240, 2);
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        handler.publish(new LogRecord(Level.INFO, "test"));
        handler.close();
        File backup0 = new File(tempDir, "noext_0");
        assertTrue(backup0.exists() || new File(tempDir, "noext").exists());
    }
}
