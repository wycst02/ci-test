package io.github.wycst.wastnet.http.annotation;

import java.util.HashMap;
import java.util.Map;

/**
 * Serialization/deserialization configuration for endpoint methods.
 * <p>
 * Each endpoint gets its own instance, constructed during scanning.
 * The framework populates it from global defaults and per-endpoint settings.
 *
 * @author wangyc
 */
public class ConverterConfig {

    private boolean pretty;
    private boolean skipNull;
    private String dateFormat;
    private final Map<String, String> properties = new HashMap<String, String>();

    /** Default constructor — leaves all fields at defaults. */
    public ConverterConfig() {
    }

    // ── Standard properties ──

    /** @return whether to pretty-print serialized output */
    public boolean isPretty() { return pretty; }

    /**
     * @param pretty whether to pretty-print serialized output
     * @return this for chaining
     */
    public ConverterConfig pretty(boolean pretty) { this.pretty = pretty; return this; }

    /** @return whether null-valued fields are excluded from output */
    public boolean isSkipNull() { return skipNull; }

    /**
     * @param skipNull whether null-valued fields are excluded from output
     * @return this for chaining
     */
    public ConverterConfig skipNull(boolean skipNull) { this.skipNull = skipNull; return this; }

    /** @return the date/time format pattern, or empty string */
    public String getDateFormat() { return dateFormat; }

    /**
     * @param dateFormat date/time format pattern (e.g. "yyyy-MM-dd HH:mm:ss")
     * @return this for chaining
     */
    public ConverterConfig dateFormat(String dateFormat) { this.dateFormat = dateFormat; return this; }

    // ── Custom properties (converter-specific) ──

    /** @return the underlying custom properties map (mutable) */
    public Map<String, String> getProperties() { return properties; }

    /**
     * @param key   custom property key
     * @param value custom property value
     * @return this for chaining
     */
    public ConverterConfig property(String key, String value) { properties.put(key, value); return this; }

    /**
     * @param props bulk properties to merge in
     * @return this for chaining
     */
    public ConverterConfig properties(Map<String, String> props) { properties.putAll(props); return this; }

    /**
     * @param key property key
     * @return the value, or {@code null} if not set
     */
    public String getProperty(String key) { return properties.get(key); }

    /**
     * @param key          property key
     * @param defaultValue returned when key is absent
     * @return the value, or {@code defaultValue} if not set
     */
    public String getProperty(String key, String defaultValue) {
        String v = properties.get(key);
        return v != null ? v : defaultValue;
    }
}
