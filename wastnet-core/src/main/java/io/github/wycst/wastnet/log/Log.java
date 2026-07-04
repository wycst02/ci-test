package io.github.wycst.wastnet.log;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Log instance providing info / debug / warn / error methods.
 *
 * @Date 2025/6/15
 */
public final class Log {

    private final String loggerName;
    private final Logger logger;
    private boolean enabled;
    private boolean callerInfoEnabled;

    Log(Class<?> nameClass) {
        this.loggerName = abbreviateClassName(nameClass.getName());
        this.logger = Logger.getLogger(loggerName);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }

    Log(String name, Logger logger) {
        this.loggerName = name;
        this.logger = logger;
    }

    void addHandler(java.util.logging.Handler handler) {
        logger.addHandler(handler);
    }

    void setLevel(Level level) {
        logger.setLevel(level);
    }

    /**
     * Enable or disable this logger independently of java.util.logging.Level.
     * When disabled, {@link #trace(String, Object...)} will not output.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Return whether this logger is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enable caller class/method/line info in the log output and return this
     * instance for chaining.
     * <p>
     * When enabled (default), each log entry includes the caller's class name,
     * method name, and line number information obtained via stack trace walking.
     */
    public Log callerInfoEnabled() {
        this.callerInfoEnabled = true;
        return this;
    }

    public void debug(String msg, Object... args) {
        log(Level.CONFIG, msg, args);
    }

    public void trace(String msg, Object... args) {
        if (!enabled) {
            return;
        }
        log(Level.FINE, msg, args);
    }

    public void info(String msg, Object... args) {
        log(Level.INFO, msg, args);
    }

    public void warn(String msg, Object... args) {
        log(Level.WARNING, msg, args);
    }

    public void error(String msg, Object... args) {
        log(Level.SEVERE, msg, args);
    }

    public void error(String msg, Throwable throwable, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            LogRecord logRecord = new LogRecord(Level.SEVERE, msg);
            logRecord.setParameters(args);
            logRecord.setThrown(throwable);
            fillCallerInfo(logRecord);
            logger.log(logRecord);
        }
    }

    private void log(Level level, String msg, Object[] args) {
        if (logger.isLoggable(level)) {
            LogRecord logRecord = new LogRecord(level, msg);
            logRecord.setParameters(args);
            fillCallerInfo(logRecord);
            logger.log(logRecord);
        }
    }

    private void fillCallerInfo(LogRecord logRecord) {
        if (!callerInfoEnabled) {
            logRecord.setLoggerName(loggerName);
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // stack[0] = getStackTrace()
        // stack[1] = fillCallerInfo()
        // stack[2] = log()
        // stack[3] = info()/debug()/warn()/error()/trace()
        // stack[4] = actual caller
        if (stack.length > 4) {
            StackTraceElement caller = stack[4];
            String fullClass = caller.getClassName();
            logRecord.setLoggerName(fullClass + "." + caller.getMethodName() + ":" + caller.getLineNumber());
        } else {
            logRecord.setLoggerName(loggerName);
        }
    }

    private static String abbreviateClassName(String className) {
        if (className.indexOf('.') == -1) {
            return className;
        }
        if (className.length() < 40) {
            return className;
        }
        return className.replaceAll("(\\w)\\w*[.]", "$1.");
    }
}
