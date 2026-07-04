package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.security.cert.X509Certificate;

/**
 * SSL/TLS integration test — starts an HTTPS server using PEM certificates,
 * verifies TLS handshake, encrypted request/response, and certificate validation.
 *
 * @author wangyc
 */
public class SslTlsIntegrationTest {

    private static HTTPServer server;
    private static int port;

    @BeforeAll
    public static void startServer() {
        port = findFreePort();
        server = HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest request,
                                       HttpResponse response) throws Throwable {
                        String path = request.getRequestUri();
                        if ("/secure".equals(path)) {
                            response.status(HttpStatus.OK)
                                    .contentType("application/json;charset=utf-8")
                                    .body("{\"secure\":true,\"ssl\":" + request.isSSL() + "}".getBytes("UTF-8"));
                        } else if ("/echo".equals(path)) {
                            byte[] body = request.getBodyData();
                            response.status(HttpStatus.OK)
                                    .contentType("text/plain")
                                    .body(body != null ? body : new byte[0]);
                        } else {
                            response.status(HttpStatus.OK)
                                    .body("HTTPS OK".getBytes());
                        }
                    }
                })
                .startupBannerEnabled(false)
                .start();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testHttpsGetRequest() throws Exception {
        HttpsURLConnection conn = httpsGet("https://localhost:" + port + "/secure");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("\"secure\":true"), "Response should indicate secure flag");
        Assertions.assertTrue(body.contains("\"ssl\":true"), "Response should indicate SSL is active");
    }

    @Test
    public void testHttpsPlainTextEndpoint() throws Exception {
        HttpsURLConnection conn = httpsGet("https://localhost:" + port + "/");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertEquals("HTTPS OK", body);
    }

    @Test
    public void testHttpsPostRequest() throws Exception {
        HttpsURLConnection conn = httpsPost("https://localhost:" + port + "/echo", "encrypted-payload");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertEquals("encrypted-payload", body);
    }

    @Test
    public void testTlsCipherSuite() throws Exception {
        HttpsURLConnection conn = httpsGet("https://localhost:" + port + "/");
        Assertions.assertEquals(200, conn.getResponseCode());
        // Verify that a TLS cipher suite was negotiated
        String cipherSuite = conn.getCipherSuite();
        Assertions.assertNotNull(cipherSuite, "TLS cipher suite should be negotiated");
        Assertions.assertTrue(cipherSuite.startsWith("TLS"), "Cipher suite should start with TLS");
    }

    @Test
    public void testServerCertificate() throws Exception {
        HttpsURLConnection conn = httpsGet("https://localhost:" + port + "/");
        Assertions.assertEquals(200, conn.getResponseCode());
        // Verify server certificate is present
        java.security.cert.Certificate[] certs = conn.getServerCertificates();
        Assertions.assertNotNull(certs, "Server certificates should be present");
        Assertions.assertTrue(certs.length > 0, "At least one certificate should be present");
        // Verify it's an X509 certificate
        Assertions.assertTrue(certs[0] instanceof X509Certificate, "Certificate should be X509");
        X509Certificate x509 = (X509Certificate) certs[0];
        // Verify the certificate subject contains localhost
        String subjectDN = x509.getSubjectDN().getName();
        Assertions.assertTrue(subjectDN.contains("localhost"), "Certificate should be for localhost");
    }

    @Test
    public void testHttpsMultipleRequests() throws Exception {
        // Verify keep-alive works over TLS
        for (int i = 0; i < 3; ++i) {
            HttpsURLConnection conn = httpsGet("https://localhost:" + port + "/");
            Assertions.assertEquals(200, conn.getResponseCode(), "Request " + i + " should succeed");
            Assertions.assertEquals("HTTPS OK", readResponse(conn));
        }
    }

    @Test
    public void testPlaintextToSslPortFails() throws Exception {
        // Sending a plain HTTP request to the HTTPS port should fail
        try {
            URL url = new URL("http://localhost:" + port + "/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getResponseCode();
            // If we get here without exception, the connection shouldn't return a valid HTTP response
            Assertions.fail("Plain HTTP to TLS port should fail");
        } catch (Exception e) {
            // Expected: SSL handshake failure or connection reset
            Assertions.assertTrue(e instanceof java.io.IOException || e instanceof javax.net.ssl.SSLException, "Should fail with IO or SSL exception");
        }
    }

    // ==================== HTTPS helpers ====================

    /**
     * Create a trust-all SSL context for testing with self-signed certificates.
     */
    private static SSLContext createTrustAllSSLContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }

    private static HttpsURLConnection httpsGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(createTrustAllSSLContext().getSocketFactory());
        conn.setHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        });
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        return conn;
    }

    private static HttpsURLConnection httpsPost(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(createTrustAllSSLContext().getSocketFactory());
        conn.setHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        });
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.getOutputStream().write(body.getBytes("UTF-8"));
        conn.connect();
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18083;
        }
    }
}
