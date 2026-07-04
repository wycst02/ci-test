package io.github.wycst.wastnet.env;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import sun.misc.Unsafe;

import javax.net.ssl.SSLEngine;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
/**
 * Adaptation to different versions of JDK
 *
 * @Date 2024/2/19 14:17
 * @Created by wangyc
 */
@SuppressWarnings("unchecked")
public abstract class RuntimeEnv {

    static final Log LOG = LogFactory.getLog(RuntimeEnv.class);

    public static final RuntimeEnv INSTANCE;

    public static final float JDK_VERSION;
    public static final boolean JDK9PLUS;
    static final Unsafe UNSAFE;
    public static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    public static final long STRING_VALUE_OFFSET;
    public static final long BYTE_ARRAY_OFFSET;
    static {
        Unsafe instance;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            instance = (Unsafe) theUnsafeField.get((Object) null);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        UNSAFE = instance;

        Field valueField;
        long valueOffset = -1;
        try {
            valueField = String.class.getDeclaredField("value");
            valueOffset = UNSAFE.objectFieldOffset(valueField);
        } catch (Exception e) {
            LOG.warn("Failed to get String.value offset via Unsafe: {}", e.getMessage());
        }
        STRING_VALUE_OFFSET = valueOffset;
        BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    }

    public RuntimeEnv() {
    }

    static {

        float jdkVersion = 1.8f;
        try {
            String version = System.getProperty("java.specification.version");
            if (version != null) {
                jdkVersion = Float.parseFloat(version);
            }
        } catch (Throwable e) {
            LOG.warn("Failed to parse JDK version: {}", e.getMessage());
        }
        JDK_VERSION = jdkVersion;
        JDK9PLUS = jdkVersion >= 9f;
        Class<? extends RuntimeEnv> adapterClass;
        RuntimeEnv adapterInstance;
        try {
            if (JDK9PLUS) {
                adapterClass = (Class<? extends RuntimeEnv>) Class.forName("io.github.wycst.wastnet.env.RuntimeEnvJDK9Plus");
                adapterInstance = adapterClass.newInstance();
            } else {
                adapterClass = (Class<? extends RuntimeEnv>) Class.forName("io.github.wycst.wastnet.env.RuntimeEnvJDK9Below");
                adapterInstance = adapterClass.newInstance();
            }
            INSTANCE = adapterInstance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setApplicationProtocols(SSLEngine sslEngine, String[] applicationProtocols) {}

    public String getSSLApplicationProtocol(SSLEngine sslEngine) {
        return null;
    }
}
