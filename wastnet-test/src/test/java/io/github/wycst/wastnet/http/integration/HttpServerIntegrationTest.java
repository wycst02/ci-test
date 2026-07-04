package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpStatus;
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
 * Embedded integration test — starts one real HTTP server, sends multiple real HTTP requests.
 * <p>
 * Uses {@code @BeforeAll} to start the server once for all tests.
 *
 * @author wangyc
 */
public class HttpServerIntegrationTest {

    private static HTTPServer server;
    private static int port;

    @BeforeAll
    public static void startServer() {
        port = findFreePort();
        server = HTTPServer.of(port)
                .requestHandler((request, response) -> {
                    String path = request.getRequestUri();
                    String method = request.getMethod().name();
                    String agent = request.getHeader("User-Agent");
                    String name = request.getUriParameter("name");
                    byte[] body = request.getBodyData();

                    if ("/test".equals(path) && "GET".equals(method)) {
                        response.status(HttpStatus.OK)
                                .contentType("text/plain;charset=utf-8")
                                .body("Hello World".getBytes());
                    } else if ("/post".equals(path) && "POST".equals(method)) {
                        response.status(HttpStatus.OK).contentType("text/plain")
                                .body(body);
                    } else if ("/missing".equals(path)) {
                        response.status(HttpStatus.NOT_FOUND).body("Not Found".getBytes());
                    } else if ("/header".equals(path)) {
                        response.status(HttpStatus.OK)
                                .body(("agent=" + agent).getBytes());
                    } else if ("/greet".equals(path)) {
                        response.status(HttpStatus.OK)
                                .body(("hello " + name).getBytes());
                    } else if ("/data".equals(path)) {
                        response.status(HttpStatus.OK)
                                .contentType("application/json")
                                .body("{\"key\":\"value\"}".getBytes());
                    } else {
                        response.status(HttpStatus.OK).body("ok".getBytes());
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
    public void testGetRequest() throws Exception {
        HttpURLConnection conn = get("/test");
        Assertions.assertEquals(200, conn.getResponseCode());
        Assertions.assertEquals("Hello World", readResponse(conn));
    }

    @Test
    public void testPostRequest() throws Exception {
        HttpURLConnection conn = post("/post", "Hello Server");
        Assertions.assertEquals(200, conn.getResponseCode());
        Assertions.assertEquals("Hello Server", readResponse(conn));
    }

    @Test
    public void testNotFound() throws Exception {
        HttpURLConnection conn = get("/missing");
        Assertions.assertEquals(404, conn.getResponseCode());
    }

    @Test
    public void testCustomHeader() throws Exception {
        HttpURLConnection conn = get("/header", "User-Agent", "IntegrationTest/1.0");
        Assertions.assertEquals(200, conn.getResponseCode());
        Assertions.assertEquals("agent=IntegrationTest/1.0", readResponse(conn));
    }

    @Test
    public void testQueryParameter() throws Exception {
        HttpURLConnection conn = get("/greet?name=world");
        Assertions.assertEquals(200, conn.getResponseCode());
        Assertions.assertEquals("hello world", readResponse(conn));
    }

    @Test
    public void testContentTypeResponse() throws Exception {
        HttpURLConnection conn = get("/data");
        Assertions.assertEquals(200, conn.getResponseCode());
        Assertions.assertTrue(conn.getContentType().contains("application/json"));
    }

    @Test
    public void testMultipleRequests() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpURLConnection conn = get("/" + i);
            Assertions.assertEquals(200, conn.getResponseCode());
        }
    }

    // ==================== HttpClient helpers ====================

    private static HttpURLConnection get(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.connect();
        return conn;
    }

    private static HttpURLConnection get(String path, String headerKey, String headerVal) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty(headerKey, headerVal);
        conn.connect();
        return conn;
    }

    private static HttpURLConnection post(String path, String body) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes());
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
            return 18080;
        }
    }
}
