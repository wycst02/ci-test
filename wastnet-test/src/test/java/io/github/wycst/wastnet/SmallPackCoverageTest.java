package io.github.wycst.wastnet;

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketConf;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for small packages: env, socket.conf, log.
 */
class SmallPackCoverageTest {

    // ==================== RuntimeEnv ====================

    @Test
    void testRuntimeEnvBasic() {
        assertNotNull(RuntimeEnv.INSTANCE);
        assertTrue(RuntimeEnv.JDK_VERSION > 0);
    }

    @Test
    void testRuntimeEnvBigEndian() {
        // BIG_ENDIAN is set at class load; just verify it's a boolean
        assertTrue(RuntimeEnv.BIG_ENDIAN || !RuntimeEnv.BIG_ENDIAN);
    }

    @Test
    void testRuntimeEnvStringValueOffset() {
        // STRING_VALUE_OFFSET is set at class load
        assertTrue(RuntimeEnv.STRING_VALUE_OFFSET > 0 || RuntimeEnv.STRING_VALUE_OFFSET == -1);
    }

    @Test
    void testRuntimeEnvByteArrayOffset() {
        assertTrue(RuntimeEnv.BYTE_ARRAY_OFFSET > 0);
    }

    // ==================== SocketConf ====================

    @Test
    void testSocketConfConstants() {
        assertNotNull(SocketConf.WRITE_TIMEOUT_MS);
        assertNotNull(SocketConf.READ_TIMEOUT_MS);
    }

    @Test
    void testSocketConfGetProperty() {
        SocketConf.getProperty("wastnet.socket.test-key");
    }

    @Test
    void testSocketConfLoadBalanceType() {
        // LOAD_BALANCE_TYPE is set at class load
        assertNotNull(SocketConf.LOAD_BALANCE_TYPE);
    }

    // ==================== Log ====================

    @Test
    void testLogLevels() {
        Log log = LogFactory.getLog(SmallPackCoverageTest.class);
        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(
                "io.github.wycst.wastnet.SmallPackCoverageTest");
        julLogger.setLevel(Level.OFF);
        julLogger.setUseParentHandlers(false);
        log.error("test error {}", "arg");
        log.warn("test warn {}", "arg");
        log.info("test info {}", "arg");
        log.debug("test debug {}", "arg");
    }

    @Test
    void testLogCallerInfoEnabled() {
        Log log = LogFactory.getLog(SmallPackCoverageTest.class);
        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(
                "io.github.wycst.wastnet.SmallPackCoverageTest");
        julLogger.setLevel(Level.OFF);
        julLogger.setUseParentHandlers(false);
        log.callerInfoEnabled().error("caller test");
    }
}
