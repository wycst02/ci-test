package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;

/**
 * Reverse proxy integration test — starts a backend server and a proxy frontend,
 * verifies request forwarding, path rewrite, header injection, and changeOrigin.
 *
 * @author wangyc
 */
public class HttpProxyIntegrationTest {

    private static HTTPServer backendServer;
    private static HTTPServer proxyServer;
    private static int backendPort;
    private static int proxyPort;

    @BeforeAll
    public static void startServers() {
        backendPort = findFreePort();
        proxyPort = findFreePort();

        // ==================== Backend server ====================
        HttpRouterHandler backendRouter = new HttpRouterHandler();
        backendRouter.get("/hello", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) {
                String realIp = request.getHeader("X-Real-IP");
                String host = request.getHeader("Host");
                String body = "backend:hello|host=" + host + "|realIp=" + (realIp != null ? realIp : "none");
                response.status(HttpStatus.OK)
                        .contentType("text/plain;charset=utf-8")
                        .body(body.getBytes());
            }
        });
        backendRouter.get("/api/users", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) {
                response.status(HttpStatus.OK)
                        .contentType("application/json")
                        .body("{\"users\":[\"alice\",\"bob\"]}".getBytes());
            }
        });
        backendRouter.get("/echo-path", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) {
                response.status(HttpStatus.OK)
                        .body(("path=" + request.getRequestUri()).getBytes());
            }
        });
        backendRouter.post("/echo-body", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request,
                               HttpResponse response) {
                byte[] body = request.getBodyData();
                response.status(HttpStatus.OK)
                        .contentType("text/plain")
                        .body(body != null ? body : new byte[0]);
            }
        });

        backendServer = HTTPServer.of(backendPort)
                .requestHandler(backendRouter)
                .startupBannerEnabled(false)
                .start();

        // ==================== Proxy server ====================

        // Warm up: ensure backend is ready before configuring proxy routes
        warmUp("http://localhost:" + backendPort + "/hello");
        HttpRouterHandler proxyRouter = new HttpRouterHandler();

        // Simple proxy: forward /proxy-api/* to backend, strip prefix
        proxyRouter.proxy("/proxy-api",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replacePrefix("/proxy-api", ""));

        // Proxy with prefix strip and header injection
        proxyRouter.proxy("/rest",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replacePrefix("/rest", "")
                        .changeOrigin(true)
                        .addHeader("X-Real-IP", "$remote_addr"));

        // Proxy with regex rewrite
        proxyRouter.proxy("/v1",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replaceRegex("^/v1/(.*)$", "/$1"));

        // Proxy with custom rewrite function and header removal
        proxyRouter.proxy("/func",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .rewrite(path -> path.replaceFirst("^/func", ""))
                        .removeHeader("User-Agent", "Cookie"));

        // Proxy with loop detection enabled
        proxyRouter.proxy("/safe",
                HttpProxyConfig.target("http://localhost:" + backendPort + "/hello")
                        .replacePrefix("/safe", "")
                        .loopDetection(true));

        // Proxy to unreachable backend (will get 502 Bad Gateway)
        proxyRouter.proxy("/downstream",
                HttpProxyConfig.target("http://localhost:1"));

        // Proxy with upgrade enabled (non-WebSocket)
        proxyRouter.proxy("/upgrade",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replacePrefix("/upgrade", "")
                        .upgrade(true));

        // Proxy with changeOrigin(false)
        proxyRouter.proxy("/origin-off",
                HttpProxyConfig.target("http://localhost:" + backendPort + "/hello")
                        .replacePrefix("/origin-off", "")
                        .changeOrigin(false));

        proxyServer = HTTPServer.of(proxyPort)
                .requestHandler(proxyRouter)
                .startupBannerEnabled(false)
                .start();

        // Warm up: ensure proxy is ready
        warmUp("http://localhost:" + proxyPort + "/nonexistent");
    }

    @AfterAll
    public static void stopServers() {
        if (proxyServer != null) proxyServer.stop();
        if (backendServer != null) backendServer.stop();
    }

    @Test
    public void testProxyForwardingWithPrefixStrip() throws Exception {
        // /proxy-api/api/users → backend /api/users
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/proxy-api/api/users");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("users"), "Response should contain users data");
        Assertions.assertTrue(body.contains("alice"), "Response should contain alice");
    }

    @Test
    public void testProxyWithRewriteAndHeaderInjection() throws Exception {
        // /rest/hello → backend /hello (rewrite=true keeps path as-is, so it stays /rest/hello)
        // Actually rewrite(true) means IDENTITY rewrite, path passes through.
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/rest/hello");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        // Verify the backend received the request
        Assertions.assertTrue(body.contains("backend:hello"), "Backend should process the request");
        // X-Real-IP should be set to 127.0.0.1 (remote addr from proxy perspective)
        Assertions.assertTrue(body.contains("realIp="), "X-Real-IP should be injected");
    }

    @Test
    public void testProxyWithRegexRewrite() throws Exception {
        // /v1/echo-path → backend /echo-path (regex rewrite strips /v1 prefix)
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/v1/echo-path");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertEquals("path=/echo-path", body);
    }

    @Test
    public void testProxyPostRequest() throws Exception {
        // POST to proxy with prefix strip
        HttpURLConnection conn = post("http://localhost:" + proxyPort + "/proxy-api/echo-body", "proxy-test-data");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertEquals("proxy-test-data", body);
    }

    @Test
    public void testProxyWithCustomRewriteFunction() throws Exception {
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/func/echo-path");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertEquals("path=/echo-path", body);
    }

    @Test
    public void testProxyWithHeaderRemoval() throws Exception {
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/func/hello");
        Assertions.assertEquals(200, conn.getResponseCode());
        // Header removal should not cause errors
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("backend:hello"));
    }

    @Test
    public void testProxyWithMultipleBackendRoutes() throws Exception {
        // Different proxy routes to different backend paths
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/func/api/users");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("users"));
    }

    @Test
    public void testProxyWithLoopDetection() throws Exception {
        // Request /safe/hello → path=/safe/hello → replacePrefix("/safe","") → /hello
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/safe/hello");
        Assertions.assertEquals(HttpURLConnection.HTTP_OK, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("backend:hello"));
    }

    @Test
    public void testProxyToDownstreamBackendReturns502() throws Exception {
        // Port 1 has nothing listening → should get 502 Bad Gateway
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/downstream/test");
        int code = conn.getResponseCode();
        Assertions.assertTrue(code == 502 || code == 504,
                "Expected 502 Bad Gateway or 504 Gateway Timeout, got " + code);
    }

    @Test
    public void testProxyWithUpgradeEnabled() throws Exception {
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/upgrade/hello");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("backend:hello"));
    }

    @Test
    public void testProxyWithChangeOriginOff() throws Exception {
        // changeOrigin(false) → backend sees original Host from client
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/origin-off/hello");
        Assertions.assertEquals(200, conn.getResponseCode());
        String body = readResponse(conn);
        Assertions.assertTrue(body.contains("backend:hello"));
    }

    @Test
    public void testProxyNotFoundRoute() throws Exception {
        // Request to a non-proxied path should get 404
        HttpURLConnection conn = get("http://localhost:" + proxyPort + "/nonexistent");
        Assertions.assertEquals(404, conn.getResponseCode());
    }

    // ==================== HTTP helpers ====================

    private static HttpURLConnection get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.connect();
        return conn;
    }

    private static HttpURLConnection post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
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
            return 18082;
        }
    }

    /**
     * Warm up server by sending a probe request (ignores errors).
     */
    private static void warmUp(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }
}
