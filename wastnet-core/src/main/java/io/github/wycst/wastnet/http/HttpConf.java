package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.conf.Conf;
import io.github.wycst.wastnet.util.Utils;

import java.io.File;
import java.util.Properties;

/**
 * HTTP environment configuration global control class.
 * <p>
 * Supports configuration through wastnet-http.properties, system properties, or Docker environment variables.
 * <p>
 * Configuration file loading priority (from low to high):
 * <ol>
 *     <li>Source code path /wastnet-http.properties</li>
 *     <li>Config directory under source root: /config/wastnet-http.properties</li>
 *     <li>wastnet-http.properties at the same level as the JAR file</li>
 *     <li>wastnet-http.properties in the config directory at the same level as the JAR file</li>
 *     <li>wastnet-http.properties in the config directory at the parent level of the JAR file</li>
 * </ol>
 */
public final class HttpConf extends Conf {

    // ================= Configuration Values =================

    private static final Properties APP_PROPS;

    /**
     * Maximum size of a single header (key + value) in bytes.
     * Key: {@code wastnet.http.max-single-header-size}
     * <p>
     * Default: 8192, Minimum: 1
     */
    public static final int MAX_SINGLE_HEADER_SIZE;

    /**
     * Maximum size of all headers in bytes.
     * Key: {@code wastnet.http.max-http-header-size}
     * <p>
     * Default: 16KB (16384 bytes), Minimum: 1
     */
    public static final int MAX_HTTP_HEADER_SIZE;

    /**
     * Maximum length of request URI (request-target).
     * Key: {@code wastnet.http.max-uri-length}
     * <p>
     * Default: 16384, Minimum: 1
     */
    public static final int MAX_URI_LENGTH;

    /**
     * Body memory threshold in bytes for response buffering.
     * Key: {@code wastnet.http.body-memory-threshold}
     * <p>
     * Default: 512KB, Minimum: 1
     * <p>
     * Used primarily in Response: when buffer size exceeds this threshold,
     * data is flushed to channel immediately to prevent OOM.
     */
    public static final int BODY_MEMORY_THRESHOLD;

    /**
     * Maximum body size to keep in memory for request parsing.
     * Key: {@code wastnet.http.max-body-in-memory}
     * <p>
     * Default: 2MB, Minimum: 1
     * <p>
     * Used primarily in Request: bodies larger than this will be processed
     * as stream instead of being fully loaded into memory.
     */
    public static final int MAX_BODY_IN_MEMORY;

    /**
     * Maximum body size in bytes.
     * Key: {@code wastnet.http.body-max-size}
     * <p>
     * Default: -1. Non-positive value (≤ 0) means unlimited.
     * When positive, minimum value is {@link #BODY_MEMORY_THRESHOLD}.
     */
    public static final long BODY_MAX_SIZE;

    /**
     * Maximum size of a single WebSocket frame payload in bytes.
     * Key: {@code wastnet.http.max-ws-frame-size}
     * <p>
     * Default: 512MB payload (536870912), Minimum: 1
     */
    public static final int MAX_WS_FRAME_SIZE;

    /**
     * Enable temporary file generation.
     * Key: {@code wastnet.http.enable-temp-file}
     * <p>
     * Default: true
     * <p>
     * Whether to generate temporary files when body exceeds memory threshold.
     * If disabled, multipart fields that would require temp files will be skipped.
     */
    public static final boolean ENABLE_TEMP_FILE;

    /**
     * Temporary file directory.
     * Key: {@code wastnet.http.temp-file-dir}
     * <p>
     * Default: {java.io.tmpdir}/wast-http
     */
    public static final String TEMP_FILE_DIR;

    /**
     * Temporary file prefix.
     * Key: {@code wastnet.http.temp-file-prefix}
     * <p>
     * Default: "wast_http_"
     */
    public static final String TEMP_FILE_PREFIX;

    /**
     * Default character set.
     * Key: {@code wastnet.http.default-charset}
     * <p>
     * Default: "UTF-8"
     */
    public static final String DEFAULT_CHARSET;

    /**
     * Enable GZIP compression.
     * Key: {@code wastnet.http.gzip}
     * <p>
     * Default: false
     */
    public static final boolean GZIP;

    /**
     * Minimum size threshold for GZIP compression.
     * Key: {@code wastnet.http.gzip-min-size}
     * <p>
     * No GZIP compression if smaller than this value
     * Default: 2KB, Minimum: 0 (no minimum limit)
     */
    public static final int GZIP_MIN_SIZE;

    /**
     * Write default HTTP response headers (Date, Server, Connection).
     * Key: {@code wastnet.http.header.default.enabled}
     * <p>
     * Default: true
     */
    public static final boolean WRITE_DEFAULT_HEADERS;

    /**
     * Expose Server header in HTTP response.
     * Key: {@code wastnet.http.server-header.expose}
     * <p>
     * Default: false (security best practice: hide server info)
     */
    public static final boolean EXPOSE_SERVER_HEADER;

    /**
     * Enable HTTP/1.1 Pipelining support.
     * Key: {@code wastnet.http.pipeline.enabled}
     * <p>
     * Default: false
     * <p>
     * When enabled, multiple HTTP requests in a single TCP packet will be processed.
     * When disabled, extra requests after the first one will be discarded.
     */
    public static final boolean PIPELINE_ENABLED;

    /**
     * Maximum elapsed time in milliseconds from first byte arrival to full request reception.
     * Key: {@code wastnet.http.request-timeout}
     * <p>
     * Checked non-blockingly on each NIO data arrival; exceeding this limit returns 408 Request Timeout.
     * Covers the entire request decoding lifecycle (start line, headers, and body).
     * Default: {@link Long#MAX_VALUE} (disabled). Negative or zero value is treated as {@link Long#MAX_VALUE}.
     */
    public static final long REQUEST_TIMEOUT_MS;

    /**
     * Preserve HTTP header insertion order.
     * Key: {@code wastnet.http.header.order.preserve}
     * <p>
     * Default: false (use HashMap for better performance).
     * When true, uses LinkedHashMap to maintain insertion order.
     */
    public static final boolean PRESERVE_HEADER_ORDER;

    /**
     * SSE connection timeout in milliseconds.
     * Key: {@code wastnet.http.sse-timeout-ms}
     * <p>
     * After this timeout, the SSE connection will be closed automatically.
     * Default: 1800000 (30 minutes). Non-positive value disables timeout.
     */
    public static final long SSE_TIMEOUT_MS;

    /** Comma-separated HTTP methods implemented (e.g. "GET,POST"). Null = all methods. */
    public static final String IMPLEMENTED_METHODS;

    // ================= Static Initialization Block =================

    static {
        APP_PROPS = createFileProps("wastnet-http.properties");

        // Initialize configuration values
        MAX_SINGLE_HEADER_SIZE = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.max-single-header-size", 8192)); // Default: 8192, min: 1
        MAX_HTTP_HEADER_SIZE = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.max-http-header-size", 16384)); // 16KB, min: 1
        MAX_URI_LENGTH = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.max-uri-length", 16384)); // Default: 16384, min: 1
        BODY_MEMORY_THRESHOLD = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.body-memory-threshold", 512 * 1024)); // 512KB, min: 1
        MAX_BODY_IN_MEMORY = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.max-body-in-memory", 2 * 1024 * 1024)); // 2MB, min: 1
        long bodyMaxSize = getPropLong(APP_PROPS, "wastnet.http.body-max-size", -1L);
        BODY_MAX_SIZE = bodyMaxSize <= 0 ? Long.MAX_VALUE : Math.max(bodyMaxSize, BODY_MEMORY_THRESHOLD); // Non-positive value means unlimited, otherwise min is BODY_MEMORY_THRESHOLD
        MAX_WS_FRAME_SIZE = Math.max(1, getPropInt(APP_PROPS, "wastnet.http.max-ws-frame-size", 512 * 1024 * 1024)); // Default: 512MB, min: 1
        ENABLE_TEMP_FILE = isPropTrue(APP_PROPS, "wastnet.http.enable-temp-file", true); // Default: true

        String tempDir = getProperty(APP_PROPS, "wastnet.http.temp-file-dir");
        String baseTempDir = (tempDir != null && !tempDir.trim().isEmpty()) ? tempDir.trim() : System.getProperty("java.io.tmpdir") + "/wast-http";

        // Validate and create temp directory
        File tempDirFile = new File(baseTempDir);
        if (!tempDirFile.exists()) {
            if (!tempDirFile.mkdirs()) {
                // Fallback to system temp dir if creation fails
                tempDirFile = new File(System.getProperty("java.io.tmpdir"));
                System.err.println("[HttpConf] Failed to create temp directory: " + baseTempDir + ", fallback to: " + tempDirFile.getAbsolutePath());
            }
        } else if (!tempDirFile.isDirectory()) {
            // Path exists but is not a directory, fallback
            tempDirFile = new File(System.getProperty("java.io.tmpdir"));
            System.err.println("[HttpConf] Temp path is not a directory: " + baseTempDir + ", fallback to: " + tempDirFile.getAbsolutePath());
        }
        TEMP_FILE_DIR = tempDirFile.getAbsolutePath();

        String tempPrefix = getProperty(APP_PROPS, "wastnet.http.temp-file-prefix");
        TEMP_FILE_PREFIX = (tempPrefix != null && !tempPrefix.trim().isEmpty()) ? tempPrefix.trim() : "wast_http_"; // Default: "wast_http_"

        String charset = getProperty(APP_PROPS, "wastnet.http.default-charset");
        DEFAULT_CHARSET = (charset != null && !charset.trim().isEmpty()) ? charset.trim() : "UTF-8"; // Default: "UTF-8"

        GZIP = isPropTrue(APP_PROPS, "wastnet.http.gzip"); // Default: false
        GZIP_MIN_SIZE = Math.max(0, getPropInt(APP_PROPS, "wastnet.http.gzip-min-size", 2048)); // 2KB, min: 0 (no minimum limit)
        WRITE_DEFAULT_HEADERS = isPropTrue(APP_PROPS, "wastnet.http.header.default.enabled", true); // Default: true
        EXPOSE_SERVER_HEADER = isPropTrue(APP_PROPS, "wastnet.http.server-header.expose"); // Default: false
        PIPELINE_ENABLED = isPropTrue(APP_PROPS, "wastnet.http.pipeline.enabled"); // Default: false
        long requestTimeout = getPropLong(APP_PROPS, "wastnet.http.request-timeout", Long.MAX_VALUE);
        REQUEST_TIMEOUT_MS = requestTimeout <= 0 ? Long.MAX_VALUE : requestTimeout;
        PRESERVE_HEADER_ORDER = isPropTrue(APP_PROPS, "wastnet.http.header.order.preserve"); // Default: false
        long sseTimeout = getPropLong(APP_PROPS, "wastnet.http.sse-timeout-ms", 1800000L);
        SSE_TIMEOUT_MS = sseTimeout <= 0 ? Long.MAX_VALUE : sseTimeout;

        String implementedMethods = getProperty(APP_PROPS, "wastnet.http.implemented-methods");
        IMPLEMENTED_METHODS = (implementedMethods != null && !implementedMethods.trim().isEmpty()) ? implementedMethods.trim() : null;
    }

    // ================= Public Methods =================

    /**
     * Get configuration property value.
     * <p>
     * Priority: System Properties > Environment Variables > Configuration File
     *
     * @param key configuration key
     * @return configuration value, null if not exists
     */
    public static String getProperty(String key) {
        return getProperty(APP_PROPS, key);
    }

    /**
     * Dump all HTTP configuration as JSON string.
     * <p>
     * Returns a formatted JSON string containing all configuration values.
     *
     * @return JSON string of all configurations
     */
    public static String dumpAsJson() {
        StringBuilder json = new StringBuilder(256);
        json.append('{').append('\n');
        json.append("  \"MAX_SINGLE_HEADER_SIZE\": ").append(MAX_SINGLE_HEADER_SIZE).append(",\n");
        json.append("  \"MAX_HTTP_HEADER_SIZE\": ").append(MAX_HTTP_HEADER_SIZE).append(",\n");
        json.append("  \"MAX_URI_LENGTH\": ").append(MAX_URI_LENGTH).append(",\n");
        json.append("  \"BODY_MEMORY_THRESHOLD\": ").append(BODY_MEMORY_THRESHOLD).append(",\n");
        json.append("  \"MAX_BODY_IN_MEMORY\": ").append(MAX_BODY_IN_MEMORY).append(",\n");
        json.append("  \"BODY_MAX_SIZE\": ").append(BODY_MAX_SIZE).append(",\n");
        json.append("  \"ENABLE_TEMP_FILE\": ").append(ENABLE_TEMP_FILE).append(",\n");
        json.append("  \"TEMP_FILE_DIR\": \"").append(Utils.escapeSpecialString(TEMP_FILE_DIR)).append("\",\n");
        json.append("  \"TEMP_FILE_PREFIX\": \"").append(Utils.escapeSpecialString(TEMP_FILE_PREFIX)).append("\",\n");
        json.append("  \"DEFAULT_CHARSET\": \"").append(Utils.escapeSpecialString(DEFAULT_CHARSET)).append("\",\n");
        json.append("  \"GZIP\": ").append(GZIP).append(",\n");
        json.append("  \"GZIP_MIN_SIZE\": ").append(GZIP_MIN_SIZE).append(",\n");
        json.append("  \"WRITE_DEFAULT_HEADERS\": ").append(WRITE_DEFAULT_HEADERS).append(",\n");
        json.append("  \"EXPOSE_SERVER_HEADER\": ").append(EXPOSE_SERVER_HEADER).append(",\n");
        json.append("  \"PIPELINE_ENABLED\": ").append(PIPELINE_ENABLED).append(",\n");
        json.append("  \"REQUEST_TIMEOUT_MS\": ").append(REQUEST_TIMEOUT_MS).append(",\n");
        json.append("  \"PRESERVE_HEADER_ORDER\": ").append(PRESERVE_HEADER_ORDER).append(",\n");
        json.append("  \"SSE_TIMEOUT_MS\": ").append(SSE_TIMEOUT_MS).append('\n');
        json.append('}');
        return json.toString();
    }

    /**
     * Dump all HTTP configuration as properties format.
     * <p>
     * Returns a properties string containing all configuration key-value pairs.
     *
     * @return properties string of all configurations
     */
    public static String dumpAsProperties() {
        return "# HTTP Configuration\n\n" +
                "wastnet.http.max-single-header-size=" + MAX_SINGLE_HEADER_SIZE + '\n' +
                "wastnet.http.max-http-header-size=" + MAX_HTTP_HEADER_SIZE + '\n' +
                "wastnet.http.max-uri-length=" + MAX_URI_LENGTH + '\n' +
                "wastnet.http.body-memory-threshold=" + BODY_MEMORY_THRESHOLD + '\n' +
                "wastnet.http.max-body-in-memory=" + MAX_BODY_IN_MEMORY + '\n' +
                "wastnet.http.body-max-size=" + BODY_MAX_SIZE + '\n' +
                "wastnet.http.enable-temp-file=" + ENABLE_TEMP_FILE + '\n' +
                "wastnet.http.temp-file-dir=" + TEMP_FILE_DIR + '\n' +
                "wastnet.http.temp-file-prefix=" + TEMP_FILE_PREFIX + '\n' +
                "wastnet.http.default-charset=" + DEFAULT_CHARSET + '\n' +
                "wastnet.http.gzip=" + GZIP + '\n' +
                "wastnet.http.gzip-min-size=" + GZIP_MIN_SIZE + '\n' +
                "wastnet.http.header.default.enabled=" + WRITE_DEFAULT_HEADERS + '\n' +
                "wastnet.http.server-header.expose=" + EXPOSE_SERVER_HEADER + '\n' +
                "wastnet.http.pipeline.enabled=" + PIPELINE_ENABLED + '\n' +
                "wastnet.http.request-timeout=" + REQUEST_TIMEOUT_MS + '\n' +
                "wastnet.http.header.order.preserve=" + PRESERVE_HEADER_ORDER + '\n' +
                "wastnet.http.sse-timeout-ms=" + SSE_TIMEOUT_MS + '\n';
    }
}
