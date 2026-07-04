package io.github.wycst.wastnet.log;

import java.io.File;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Log factory providing ways to obtain a {@link Log} instance:
 * <ul>
 *   <li>{@link #getLog(Class)} — class logger, outputs to console by default</li>
 *   <li>{@link #getAccessLog()} — access log, writes to {@code access.log}</li>
 *   <li>{@link #getErrorLog()} — error log, writes to {@code error.log}</li>
 *   <li>{@link #newPacketLog(String)} — create a packet log with a custom name suffix</li>
 * </ul>
 * <p>
 * Log directory is configurable via the system property {@code wastnet.log.dir},
 * defaults to {@code logs} (relative to the startup directory).
 *
 * @Date 2025/6/15
 */
public final class LogFactory {

    private static final Map<Class<?>, Log> LOGS = new ConcurrentHashMap<Class<?>, Log>();
    private static final ConsoleHandler CONSOLE_HANDLER;
    private static final String LOG_DIR;

    private static Log accessLog;
    private static Log errorLog;

    static {
        // log directory
        String dir = System.getProperty("wastnet.log.dir", "");
        LOG_DIR = dir.isEmpty() ? "logs" : dir;

        // console output handler
        CONSOLE_HANDLER = new java.util.logging.ConsoleHandler() {
            @Override
            protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
                super.setOutputStream(System.out);
            }
        };
        CONSOLE_HANDLER.setFormatter(new LogFormatter());

        // initialize file logs
        accessLog = initFileLog(LOG_DIR + File.separator + "access.log", Level.INFO, 20 * 1024 * 1024, 2);
        errorLog = initFileLog(LOG_DIR + File.separator + "error.log", Level.WARNING, 20 * 1024 * 1024, 2);
    }

    private static Log initFileLog(String filePath, Level level, int maxSize, int maxFiles) {
        String name = new File(filePath).getName();
        Logger logger = Logger.getLogger("file." + name);
        logger.setLevel(level);
        logger.setUseParentHandlers(false);
        RotatingFileHandler handler = new RotatingFileHandler(filePath, maxSize, maxFiles);
        handler.setFormatter(new LogFormatter());
        handler.setLevel(level);
        logger.addHandler(handler);
        return new Log(name, logger);
    }

    /**
     * Return a class-level logger instance (outputs to console).
     */
    public static Log getLog(Class<?> logCls) {
        synchronized (logCls) {
            Log log = LOGS.get(logCls);
            if (log == null) {
                log = new Log(logCls);
                log.addHandler(CONSOLE_HANDLER);
                LOGS.put(logCls, log);
            }
            return log;
        }
    }

    /**
     * Return the access log (defaults to {@code logs/access.log}).
     */
    public static Log getAccessLog() {
        return accessLog;
    }

    /**
     * Return the error log (defaults to {@code logs/error.log}).
     */
    public static Log getErrorLog() {
        return errorLog;
    }

    /**
     * Create a new packet log with a custom name suffix.
     * <p>
     * Useful when multiple server instances each need their own packet log.
     * File will be {@code logs/packet_<i>name</i>.log}.
     * Disabled by default; call {@link Log#setEnabled(boolean) setEnabled(true)}
     * to enable output.
     *
     * @param name the suffix for the log file name (e.g. {@code "443"} produces {@code packet_443.log})
     * @return a new Log instance
     */
    public static Log newPacketLog(String name) {
        String filePath = LOG_DIR + File.separator + "packet_" + name + ".log";
        Log log = initFileLog(filePath, Level.FINE, 20 * 1024 * 1024, 2);
        log.setEnabled(false);
        return log;
    }

    private LogFactory() {
    }
}
