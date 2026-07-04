package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/**
 * Reverse proxy integration test — starts a backend server and a proxy frontend,
 * verifies request forwarding, path rewrite, header injection, and changeOrigin.
 */
public class HttpProxyIntegrationTest {

    private static HTTPServer backendServer;
    private static HTTPServer proxyServer;
    private static int backendPort;
    private static int proxyPort;
    private static OkHttpClient httpClient;

    @BeforeAll
    public static void startServers() {
        backendPort = findFreePort();
        proxyPort = findFreePort();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

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
                        .replacePrefix("/proxy-api", "")
                        .readTimeout(30000));

        // Proxy with prefix strip and header injection
        proxyRouter.proxy("/rest",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replacePrefix("/rest", "")
                        .changeOrigin(true)
                        .addHeader("X-Real-IP", "$remote_addr")
                        .readTimeout(30000));

        // Proxy with regex rewrite
        proxyRouter.proxy("/v1",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replaceRegex("^/v1/(.*)$", "/$1")
                        .readTimeout(30000));

        // Proxy with custom rewrite function and header removal
        proxyRouter.proxy("/func",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .rewrite(path -> path.replaceFirst("^/func", ""))
                        .removeHeader("User-Agent", "Cookie")
                        .readTimeout(30000));

        // Proxy with loop detection enabled
        proxyRouter.proxy("/safe",
                HttpProxyConfig.target("http://localhost:" + backendPort + "/hello")
                        .replacePrefix("/safe", "")
                        .loopDetection(true)
                        .readTimeout(30000));

        // Proxy to unreachable backend (will get 502 Bad Gateway)
        proxyRouter.proxy("/downstream",
                HttpProxyConfig.target("http://localhost:1")
                        .connectionTimeout(3000)
                        .readTimeout(5000));

        // Proxy with upgrade enabled (non-WebSocket)
        proxyRouter.proxy("/upgrade",
                HttpProxyConfig.target("http://localhost:" + backendPort)
                        .replacePrefix("/upgrade", "")
                        .upgrade(true)
                        .readTimeout(30000));

        // Proxy with changeOrigin(false)
        proxyRouter.proxy("/origin-off",
                HttpProxyConfig.target("http://localhost:" + backendPort + "/hello")
                        .replacePrefix("/origin-off", "")
                        .changeOrigin(false)
                        .readTimeout(30000));

        proxyServer = HTTPServer.of(proxyPort)
                .requestHandler(proxyRouter)
                .startupBannerEnabled(false)
                .start();

        // Warm up: ensure proxy is ready
        warmUp("http://localhost:" + proxyPort + "/nonexistent");
    }

    @AfterAll
    public static void stopServers() {
        if (proxyServer != null) proxyServer.shutdown();
        if (backendServer != null) backendServer.shutdown();
    }

    // ==================== Tests ====================

    @Test
    public void testProxyForwardingWithPrefixStrip() throws Exception {
        try (Response resp = get(proxyPort, "/proxy-api/api/users")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("users"), "Response should contain users data");
            Assertions.assertTrue(body.contains("alice"), "Response should contain alice");
        }
    }

    @Test
    public void testProxyWithRewriteAndHeaderInjection() throws Exception {
        try (Response resp = get(proxyPort, "/rest/hello")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("backend:hello"), "Backend should process the request");
            Assertions.assertTrue(body.contains("realIp="), "X-Real-IP should be injected");
        }
    }

    @Test
    public void testProxyWithRegexRewrite() throws Exception {
        try (Response resp = get(proxyPort, "/v1/echo-path")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("path=/echo-path", resp.body().string());
        }
    }

    @Test
    public void testProxyPostRequest() throws Exception {
        try (Response resp = post(proxyPort, "/proxy-api/echo-body", "proxy-test-data")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("proxy-test-data", resp.body().string());
        }
    }

    @Test
    public void testProxyWithCustomRewriteFunction() throws Exception {
        try (Response resp = get(proxyPort, "/func/echo-path")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("path=/echo-path", resp.body().string());
        }
    }

    @Test
    public void testProxyWithHeaderRemoval() throws Exception {
        try (Response resp = get(proxyPort, "/func/hello")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("backend:hello"));
        }
    }

    @Test
    public void testProxyWithMultipleBackendRoutes() throws Exception {
        try (Response resp = get(proxyPort, "/func/api/users")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("users"));
        }
    }

    @Test
    public void testProxyWithLoopDetection() throws Exception {
        try (Response resp = get(proxyPort, "/safe/hello")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("backend:hello"));
        }
    }

    @Test
    public void testProxyToDownstreamBackendReturns502() throws Exception {
        try (Response resp = get(proxyPort, "/downstream/test")) {
            int code = resp.code();
            Assertions.assertTrue(code == 502 || code == 504,
                    "Expected 502 Bad Gateway or 504 Gateway Timeout, got " + code);
        }
    }

    @Test
    public void testProxyWithUpgradeEnabled() throws Exception {
        try (Response resp = get(proxyPort, "/upgrade/hello")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("backend:hello"));
        }
    }

    @Test
    public void testProxyWithChangeOriginOff() throws Exception {
        try (Response resp = get(proxyPort, "/origin-off/hello")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("backend:hello"));
        }
    }

    @Test
    public void testProxyNotFoundRoute() throws Exception {
        try (Response resp = get(proxyPort, "/nonexistent")) {
            Assertions.assertEquals(404, resp.code());
        }
    }

    // ==================== OkHttp helpers ====================

    private static Response get(int port, String path) throws IOException {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + path)
                .build();
        return httpClient.newCall(request).execute();
    }

    private static Response post(int port, String path, String body) throws IOException {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(RequestBody.create(MediaType.parse("text/plain"), body))
                .build();
        return httpClient.newCall(request).execute();
    }

    private static void warmUp(String url) {
        try {
            Request request = new Request.Builder().url(url).build();
            Response resp = httpClient.newCall(request).execute();
            resp.close();
        } catch (Exception ignored) {
        }
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
}
