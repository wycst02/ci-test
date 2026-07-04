package io.github.wycst.wastnet.socket.tcp;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.security.cert.X509Certificate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PEMSSLContextFactory}.
 * <p>
 * Covers constructors, create(), PEM parsing, Base64 decoding.
 *
 * @author wangyc
 */
public class PEMSSLContextFactoryTest {

    // ==================== Constructor + create with classpath PEM files ====================

    @Test
    public void testCreateFromClasspathCert() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    @Test
    public void testCreateWithKeyPassword() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", "changeit");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    @Test
    public void testCreateWithEmptyPassword() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", "");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    // ==================== Base64 utility ====================

    @Test
    public void testBase64DecodeSimple() {
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("SGVsbG8="); // "Hello"
        assertNotNull(decoded);
        assertEquals("Hello", new String(decoded));
    }

    @Test
    public void testBase64DecodeWithoutPadding() {
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("SGVsbG8"); // "Hello" no padding
        assertNotNull(decoded);
        assertEquals("Hello", new String(decoded));
    }

    @Test
    public void testBase64DecodeLongString() {
        String original = "This is a longer test string for base64 decoding with multiple characters!";
        String encoded = java.util.Base64.getEncoder().encodeToString(original.getBytes());
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode(encoded);
        assertEquals(original, new String(decoded));
    }

    @Test
    public void testBase64DecodeEmpty() {
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("");
        assertNotNull(decoded);
        assertEquals(0, decoded.length);
    }

    @Test
    public void testBase64DecodeWithNewlines() {
        // Base64 with embedded newlines (as in PEM files)
        String multiLine = "U0FN\nUExF\nIFRF\nU1Q=";
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode(multiLine);
        assertEquals("SAMPLE TEST", new String(decoded).trim());
    }

    @Test
    public void testBase64DecodeSingleChar() {
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("Zg=="); // "f"
        assertEquals(1, decoded.length);
        assertEquals('f', decoded[0]);
    }

    @Test
    public void testBase64DecodeTwoChars() {
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("Zm8="); // "fo"
        assertEquals(2, decoded.length);
        assertEquals("fo", new String(decoded));
    }

    @Test
    public void testBase64DecodeInvalidChar() {
        // Invalid character '!' should not throw — implementation ignores
        byte[] decoded = PEMSSLContextFactory.Base64Utils.base64Decode("!!");
        assertNotNull(decoded);
    }
}
