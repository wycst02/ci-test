package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpConf}.
 *
 * @author wangyc
 */
public class HttpConfTest {

    @Test
    public void testDumpAsJson() {
        String json = HttpConf.dumpAsJson();
        Assertions.assertTrue(json.startsWith("{"), json);
        Assertions.assertTrue(json.endsWith("}"), json);
        Assertions.assertTrue(json.contains("\"MAX_SINGLE_HEADER_SIZE\": " + HttpConf.MAX_SINGLE_HEADER_SIZE));
        Assertions.assertTrue(json.contains("\"GZIP\": " + HttpConf.GZIP));
        Assertions.assertTrue(json.contains("\"EXPOSE_SERVER_HEADER\": " + HttpConf.EXPOSE_SERVER_HEADER));
    }

    @Test
    public void testDumpAsProperties() {
        String props = HttpConf.dumpAsProperties();
        Assertions.assertTrue(props.startsWith("# HTTP Configuration"), props);
        Assertions.assertTrue(props.contains("wastnet.http.gzip=" + HttpConf.GZIP));
        Assertions.assertTrue(props.contains("wastnet.http.gzip-min-size=" + HttpConf.GZIP_MIN_SIZE));
        Assertions.assertTrue(props.contains("wastnet.http.header.default.enabled=" + HttpConf.WRITE_DEFAULT_HEADERS));
    }

    @Test
    public void testTempDirNotEmpty() {
        Assertions.assertNotNull(HttpConf.TEMP_FILE_DIR);
        Assertions.assertFalse(HttpConf.TEMP_FILE_DIR.trim().isEmpty());
    }

    @Test
    public void testGetPropertyReturnsNullForUnknownKey() {
        Assertions.assertNull(HttpConf.getProperty("wastnet.http.nonexistent.key"));
    }
}
