package io.github.wycst.wastnet.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Unit tests for {@link LogFormatter}.
 */
public class LogFormatterTest {

    private final LogFormatter formatter = new LogFormatter();

    // ==================== replacePlaceholder ====================

    @Test
    public void testReplacePlaceholderNoPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello world", "{}");
        Assertions.assertEquals("hello world", result);
    }

    @Test
    public void testReplacePlaceholderOneParam() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}", "world");
        Assertions.assertEquals("hello world", result);
    }

    @Test
    public void testReplacePlaceholderMultipleParams() {
        String result = LogFormatter.replacePlaceholder("{} + {} = {}", "{}", 1, 2, 3);
        Assertions.assertEquals("1 + 2 = 3", result);
    }

    @Test
    public void testReplacePlaceholderMorePlaceholdersThanParams() {
        String result = LogFormatter.replacePlaceholder("a={} b={} c={}", "{}", "x", "y");
        Assertions.assertEquals("a=x b=y c={}", result);
    }

    @Test
    public void testReplacePlaceholderNullPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello", null, "world");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testReplacePlaceholderEmptyPlaceholder() {
        String result = LogFormatter.replacePlaceholder("hello", "", "world");
        Assertions.assertEquals("hello", result);
    }

    @Test
    public void testReplacePlaceholderNullParameters() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}", (Object[]) null);
        Assertions.assertEquals("hello {}", result);
    }

    @Test
    public void testReplacePlaceholderEmptyParameters() {
        String result = LogFormatter.replacePlaceholder("hello {}", "{}");
        Assertions.assertEquals("hello {}", result);
    }

    @Test
    public void testReplacePlaceholderCustomPlaceholder() {
        String result = LogFormatter.replacePlaceholder("say %s to %s", "%s", "hello", "world");
        Assertions.assertEquals("say hello to world", result);
    }

    @Test
    public void testReplacePlaceholderConsecutive() {
        String result = LogFormatter.replacePlaceholder("{}{}", "{}", "a", "b");
        Assertions.assertEquals("ab", result);
    }

    // ==================== format ====================

    @Test
    public void testFormatSimpleMessage() {
        LogRecord record = new LogRecord(Level.INFO, "test message");
        record.setLoggerName("TestLogger");
        String result = formatter.format(record);
        Assertions.assertTrue(result.contains("test message"));
        Assertions.assertTrue(result.contains("INFO"));
        Assertions.assertTrue(result.contains("TestLogger"));
    }

    @Test
    public void testFormatWithParameters() {
        LogRecord record = new LogRecord(Level.INFO, "hello {}!");
        record.setLoggerName("Test");
        record.setParameters(new Object[]{"world"});
        String result = formatter.format(record);
        Assertions.assertTrue(result.contains("hello world!"));
    }

    @Test
    public void testFormatWithException() {
        LogRecord record = new LogRecord(Level.WARNING, "error occurred");
        record.setLoggerName("Test");
        record.setThrown(new RuntimeException("test exception"));
        String result = formatter.format(record);
        Assertions.assertTrue(result.contains("test exception"));
        Assertions.assertTrue(result.contains("WARN"));
    }

    @Test
    public void testFormatWithResourceBundle() {
        LogRecord record = new LogRecord(Level.SEVERE, "key.msg");
        record.setLoggerName("Test");
        record.setResourceBundle(new java.util.ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][]{{"key.msg", "formatted error"}};
            }
        });
        String result = formatter.format(record);
        Assertions.assertTrue(result.contains("formatted error"));
        Assertions.assertTrue(result.contains("ERROR"));
    }

    @Test
    public void testFormatMissingResourceKeyFallsBackToMessage() {
        LogRecord record = new LogRecord(Level.CONFIG, "debug message");
        record.setLoggerName("Test");
        record.setResourceBundle(new java.util.ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[0][];
            }
        });
        String result = formatter.format(record);
        Assertions.assertTrue(result.contains("debug message"));
        Assertions.assertTrue(result.contains("DEBUG"));
    }

    @Test
    public void testFormatHasNewline() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("T");
        String result = formatter.format(record);
        Assertions.assertTrue(result.endsWith("\n"));
    }

    @Test
    public void testFormatTimestampAppears() {
        LogRecord record = new LogRecord(Level.INFO, "test");
        record.setLoggerName("Logger");
        String result = formatter.format(record);
        // Should contain date pattern like "2026-06-29"
        Assertions.assertTrue(result.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*"));
    }

    // ==================== getThrowableContent ====================

    @Test
    public void testGetThrowableContentNullReturnsNull() {
        Assertions.assertNull(LogFormatter.getThrowableContent(null));
    }

    @Test
    public void testGetThrowableContentContainsMessage() {
        RuntimeException ex = new RuntimeException("something went wrong");
        String content = LogFormatter.getThrowableContent(ex);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(content.contains("something went wrong"));
    }

    @Test
    public void testGetThrowableContentContainsStackTrace() {
        RuntimeException ex = new RuntimeException("stack test");
        String content = LogFormatter.getThrowableContent(ex);
        Assertions.assertTrue(content.contains("LogFormatterTest"));
    }
}
