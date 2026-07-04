package io.github.wycst.wastnet.socket.tcp;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import static io.github.wycst.wastnet.socket.tcp.PEMSSLContextFactory.Base64Utils.base64Decode;

/**
 * PEM-based SSLContextFactory, compatible with OpenSSL-generated PEM files.
 * <p>
 * Accepts PEM certificate chain ({@code -----BEGIN CERTIFICATE-----})
 * and PKCS#8 private key ({@code -----BEGIN PRIVATE KEY-----}).
 * Automatically detects whether the path is a filesystem path or classpath resource.
 * </p>
 *
 * <pre>
 * HTTPServer.of(port)
 *     .pemSSL("cert/cert.pem", "cert/server.pem")
 *     .applicationProtocols("h2", "http/1.1")
 *     .requestHandler(router).start();
 * </pre>
 *
 * @author wangyc
 */
public class PEMSSLContextFactory implements SSLContextFactory {

    private final String certPath;
    private final String keyPath;
    private final String keyPassword;
    private final String trustCertsPath;

    /**
     * Create factory with PEM certificate and private key (PKCS#8, unencrypted).
     * <p>
     * If the path does not exist on the filesystem, it will be loaded as a classpath resource.
     */
    public PEMSSLContextFactory(String certPath, String keyPath) {
        this(certPath, keyPath, null, null);
    }

    public PEMSSLContextFactory(String certPath, String keyPath, String keyPassword) {
        this(certPath, keyPath, keyPassword, null);
    }

    public PEMSSLContextFactory(String certPath, String keyPath, String keyPassword, String trustCertsPath) {
        this.certPath = certPath;
        this.keyPath = keyPath;
        this.keyPassword = keyPassword;
        this.trustCertsPath = trustCertsPath;
    }

    @Override
    public SSLContext create() {
        try {
            Certificate[] chain = loadCertificates(certPath);
            PrivateKey privateKey = loadPrivateKey(keyPath);

            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            char[] password = keyPassword != null ? keyPassword.toCharArray() : new char[0];
            keyStore.setKeyEntry("server", privateKey, password, chain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            SSLContext sslContext = SSLContext.getInstance("TLS");

            if (trustCertsPath != null) {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(null, null);
                Certificate[] trustedCerts = loadCertificates(trustCertsPath);
                for (int i = 0; i < trustedCerts.length; i++) {
                    trustStore.setCertificateEntry("ca-" + i, trustedCerts[i]);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            } else {
                sslContext.init(kmf.getKeyManagers(), null, null);
            }

            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSLContext from PEM files", e);
        }
    }

    private Certificate[] loadCertificates(String path) throws Exception {
        List<Certificate> certs = new ArrayList<Certificate>();
        List<byte[]> pemBlocks = parsePEM(openStream(path));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (byte[] der : pemBlocks) {
            certs.add(cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        return certs.toArray(new Certificate[0]);
    }

    private PrivateKey loadPrivateKey(String path) throws Exception {
        List<byte[]> pemBlocks = parsePEM(openStream(path));
        if (pemBlocks.isEmpty()) {
            throw new IllegalArgumentException("No private key found in " + path);
        }
        byte[] keyBytes = pemBlocks.get(0);
        String[] algorithms = {"RSA", "EC"};
        for (String alg : algorithms) {
            try {
                KeyFactory kf = KeyFactory.getInstance(alg);
                return kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Unsupported key type in " + path + " (only RSA and EC PKCS#8 supported)");
    }

    /**
     * Open an InputStream from filesystem path or classpath resource.
     * <p>
     * First tries the filesystem; if the file does not exist, falls back to classpath.
     */
    private static InputStream openStream(String path) throws IOException {
        File file = new File(path);
        if (file.isFile()) {
            return new FileInputStream(file);
        }
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new FileNotFoundException("PEM file not found (filesystem or classpath): " + path);
        }
        return in;
    }

    /**
     * Parse PEM stream directly (streaming, no full file load into memory).
     *
     * @param in input stream of PEM data
     * @return list of DER-encoded bytes for each PEM block
     * @throws IOException if read fails
     */
    private static List<byte[]> parsePEM(InputStream in) throws IOException {
        List<byte[]> blocks = new ArrayList<byte[]>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            StringBuilder base64 = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("-----BEGIN ")) {
                    base64 = new StringBuilder();
                } else if (line.startsWith("-----END ")) {
                    if (base64 != null) {
                        try {
                            blocks.add(base64Decode(base64.toString()));
                        } catch (IllegalArgumentException e) {
                            throw new IOException("Malformed Base64 content in PEM block", e);
                        }
                        base64 = null;
                    }
                } else if (base64 != null) {
                    base64.append(line.trim());
                }
            }
        } finally {
            reader.close();
        }
        return blocks;
    }

    /**
     * Internal Base64 decoder, compatible with JDK 6+ (no dependency on java.util.Base64 or javax.xml.bind).
     */
    static final class Base64Utils {
        private static final byte[] DECODE_TABLE = new byte[128];

        static {
            for (int i = 0; i < 128; i++) DECODE_TABLE[i] = -1;
            for (int i = 'A'; i <= 'Z'; i++) DECODE_TABLE[i] = (byte) (i - 'A');
            for (int i = 'a'; i <= 'z'; i++) DECODE_TABLE[i] = (byte) (i - 'a' + 26);
            for (int i = '0'; i <= '9'; i++) DECODE_TABLE[i] = (byte) (i - '0' + 52);
            DECODE_TABLE['+'] = 62;
            DECODE_TABLE['/'] = 63;
        }

        static byte[] base64Decode(String text) {
            byte[] src = text.getBytes();
            int len = 0;
            for (byte b : src) {
                if (b != '=' && b != '\n' && b != '\r' && b != ' ' && b != '\t') len++;
            }
            byte[] out = new byte[len * 3 / 4];
            int p = 0, bits = 0, count = 0;
            for (byte b : src) {
                if (b == '=') break;
                if (b == '\n' || b == '\r' || b == ' ' || b == '\t') continue;
                bits = (bits << 6) | (DECODE_TABLE[b] & 0xFF);
                if (++count == 4) {
                    out[p++] = (byte) (bits >> 16);
                    out[p++] = (byte) (bits >> 8);
                    out[p++] = (byte) bits;
                    bits = 0;
                    count = 0;
                }
            }
            if (count == 3) {
                bits <<= 6;
                out[p++] = (byte) (bits >> 16);
                out[p++] = (byte) (bits >> 8);
            } else if (count == 2) {
                bits <<= 12;
                out[p++] = (byte) (bits >> 16);
            }
            if (p != out.length) {
                byte[] trimmed = new byte[p];
                System.arraycopy(out, 0, trimmed, 0, p);
                return trimmed;
            }
            return out;
        }
    }
}
