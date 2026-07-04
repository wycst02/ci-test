package io.github.wycst.wastnet.env;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Coverage tests for {@link RuntimeEnv} and its subclasses.
 */
public class RuntimeEnvTest {

    @Test
    public void testStaticFieldsInitialized() {
        Assertions.assertTrue(RuntimeEnv.JDK_VERSION > 0);
        Assertions.assertNotNull(RuntimeEnv.INSTANCE);
        Assertions.assertTrue(RuntimeEnv.STRING_VALUE_OFFSET >= 0);
        Assertions.assertTrue(RuntimeEnv.BYTE_ARRAY_OFFSET >= 0);
    }

    @Test
    public void testInstanceTypeOnCurrentJdk() {
        if (RuntimeEnv.JDK9PLUS) {
            Assertions.assertTrue(RuntimeEnv.INSTANCE instanceof RuntimeEnvJDK9Plus);
        } else {
            Assertions.assertTrue(RuntimeEnv.INSTANCE instanceof RuntimeEnvJDK9Below);
        }
    }

    @Test
    public void testGetSSLApplicationProtocolReturnsNull() {
        Assertions.assertNull(RuntimeEnv.INSTANCE.getSSLApplicationProtocol(null));
    }

    @Test
    public void testJDK9BelowInstantiation() {
        RuntimeEnvJDK9Below below = new RuntimeEnvJDK9Below();
        Assertions.assertNotNull(below);
        Assertions.assertNull(below.getSSLApplicationProtocol(null));
        // setApplicationProtocols is a no-op in the base class (not overridden in JDK9Below)
        below.setApplicationProtocols(null, null);
    }
}
