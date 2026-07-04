package io.github.wycst.wastnet.socket.conf;

import io.github.wycst.wastnet.env.RuntimeEnv;

import java.util.Properties;

public final class SocketConf extends Conf {

    /**
     * Enable virtual thread.
     * Key: {@code wastnet.socket.virtual-thread.enabled}
     */
    public static final boolean ENABLE_VIRTUAL_THREAD;

    /**
     * Select timeout in milliseconds.
     * Key: {@code wastnet.socket.select-timeout-ms}
     * <p>
     * Range: 10-100ms (capped at 100ms)
     */
    public static final long SELECT_TIMEOUT_MS;

    /**
     * Select empty poll count threshold.
     * Key: {@code wastnet.socket.select-empty-count}
     */
    public static final int SELECT_EMPTY_COUNT;

    /**
     * Default sync runner.
     * Key: {@code wastnet.socket.default-sync-runner}
     * <p>
     * Example: -Dwastnet.socket.default-sync-runner=true
     */
    public static final boolean DEFAULT_SYNC_RUNNER;

    private static final Properties APP_PROPS;
    public static final boolean WINDOWS_PLATFORM;

    static {
        boolean isWindows = false;
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            isWindows = osName.contains("win");
        } catch (Throwable throwable) {
        }
        WINDOWS_PLATFORM = isWindows;
    }

    /**
     * Maximum concurrent requests (runner thread pool max size).
     * Key: {@code wastnet.socket.max-concurrent}
     * <p>
     * Default: CPU cores * 200
     */
    public static final int MAX_CONCURRENT;

    /**
     * Load balance strategy for TCP server worker selection.
     * Key: {@code wastnet.socket.load-balance-strategy}
     * <p>
     * Optional values (case-sensitive):
     * <ul>
     * <li>{@code ROUND_ROBIN} – Assign clients to workers in round-robin order (default)</li>
     * <li>{@code LEAST_CONN} – Assign clients to the worker with the fewest active connections</li>
     * </ul>
     * Example: {@code -Dwastnet.socket.load-balance-strategy=LEAST_CONN}
     */
    public static String LOAD_BALANCE_TYPE;

    /**
     * SSL handshake timeout in milliseconds.
     * Key: {@code wastnet.socket.ssl.handshake-timeout-ms}
     * <p>
     * Default: 5000 (5 seconds), 0 = no timeout
     * <p>
     * SSL handshake timeout period (milliseconds)
     */
    public static final long SSL_HANDSHAKE_TIMEOUT_MS;

    /**
     * Read timeout in milliseconds for blocking read operations.
     * Key: {@code wastnet.socket.read-timeout-ms}
     * <p>
     * Default: 0 (unlimited wait), 0 = no timeout
     */
    public static final long READ_TIMEOUT_MS;

    /**
     * Write timeout in milliseconds for channel write operations.
     * Key: {@code wastnet.socket.write-timeout-ms}
     * <p>
     * Default: 30000 (30 seconds), 0 = no timeout
     */
    public static final long WRITE_TIMEOUT_MS;

    private static final boolean USE_LEAST_CONNECTIONS;

    static {
        APP_PROPS = createFileProps("wastnet-socket.properties");

        ENABLE_VIRTUAL_THREAD = RuntimeEnv.JDK_VERSION >= 21f && isPropTrue(APP_PROPS, "wastnet.socket.virtual-thread.enabled");
        SELECT_TIMEOUT_MS = Math.min(100, getPropInt(APP_PROPS, "wastnet.socket.select-timeout-ms", 100)); // 10 - 100ms
        SELECT_EMPTY_COUNT = getPropInt(APP_PROPS, "wastnet.socket.select-empty-count", 1024);
        DEFAULT_SYNC_RUNNER = isPropTrue(APP_PROPS, "wastnet.socket.default-sync-runner");
        // Maximum concurrent requests (default: CPU cores * 200)
        int cpuCores = Runtime.getRuntime().availableProcessors();
        MAX_CONCURRENT = getPropInt(APP_PROPS, "wastnet.socket.max-concurrent", cpuCores * 200);
        // Load balance strategy
        String lbType = APP_PROPS.getProperty("wastnet.socket.load-balance-strategy");
        LOAD_BALANCE_TYPE = lbType != null ? lbType : "ROUND_ROBIN";
        USE_LEAST_CONNECTIONS = "LEAST_CONN".equals(LOAD_BALANCE_TYPE);
        // SSL handshake timeout (5 seconds)
        SSL_HANDSHAKE_TIMEOUT_MS = getPropLong(APP_PROPS, "wastnet.socket.ssl.handshake-timeout-ms", 5000L);
        // Read timeout (0 = unlimited)
        READ_TIMEOUT_MS = Math.max(0L, getPropLong(APP_PROPS, "wastnet.socket.read-timeout-ms", 0L));
        // Write timeout (30 seconds)
        WRITE_TIMEOUT_MS = Math.max(0, getPropLong(APP_PROPS, "wastnet.socket.write-timeout-ms", 30000L));
    }

    public static boolean useLoadBalanceLeastConnections() {
        return USE_LEAST_CONNECTIONS;
    }

    public static String getProperty(String key) {
        return getProperty(APP_PROPS, key);
    }
}
