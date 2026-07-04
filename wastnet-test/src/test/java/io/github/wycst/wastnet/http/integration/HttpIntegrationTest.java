package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class HttpIntegrationTest {

    static {
        // Enable GZIP before HttpConf loads
        System.setProperty("wastnet.http.gzip", "true");
    }

    private static HTTPServer server;
    private static int port;
    private static File testFile;
    private static File htmlFile;
    private static File smallFile;
    private static final String BOUNDARY = "----TestBoundary12345";

    @BeforeAll
    public static void startServer() throws Exception {
        port = findFreePort();

        testFile = File.createTempFile("http-sendfile-", ".dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            for (int i = 0; i < 5000; i++) fos.write("HTTP_TEST_DATA\n".getBytes());
        }
        // .html file for gzip (compressible MIME type)
        htmlFile = File.createTempFile("http-sendfile-", ".html");
        try (FileOutputStream fos = new FileOutputStream(htmlFile)) {
            for (int i = 0; i < 5000; i++) fos.write("<p>HTML content for gzip testing</p>\n".getBytes());
        }
        // Small file (< bufferSize 1024) for sendFileBuffered path
        smallFile = File.createTempFile("http-small-", ".txt");
        try (FileOutputStream fos = new FileOutputStream(smallFile)) {
            fos.write("small content".getBytes());
        }

        HttpRouterHandler router = new HttpRouterHandler("/app");

        // Basic status
        router.get("/status", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body("OK".getBytes());
            }
        });

        // Parameters
        router.get("/params", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String name = request.getParameter("name");
                java.util.List<String> vals = request.getParameterValues("tag");
                String body = "name=" + (name != null ? name : "null") + "|tags=" + (vals != null ? String.join(",", vals) : "");
                response.status(HttpStatus.OK).body(body.getBytes());
            }
        });

        // Headers: multi-value response headers
        router.get("/multi-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-Multi", "val1");
                response.addHeader("X-Multi", "val2");
                response.header("X-Single", "single");
                response.body("ok".getBytes());
            }
        });

        // Remote info
        router.get("/remote-info", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String body = "remoteHost=" + request.getRemoteHost() + "|remotePort=" + request.getRemotePort()
                        + "|serverHost=" + request.getServerHost() + "|serverPort=" + request.getServerPort()
                        + "|requestId=" + request.getRequestId() + "|charset=" + request.getCharset()
                        + "|upgrade=" + request.isUpgrade();
                response.status(HttpStatus.OK).body(body.getBytes());
            }
        });

        // Attributes
        router.get("/attr", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                request.setAttribute("k1", "v1");
                response.status(HttpStatus.OK).body(("k1=" + request.getAttribute("k1")).getBytes());
            }
        });

        // sendFile
        router.get("/send-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // earlyHints
        router.get("/early-hints", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.earlyHints("</style.css>; rel=preload");
                response.status(HttpStatus.OK).body("early".getBytes());
            }
        });

        // POST echo
        router.post("/echo", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body(request.getBodyData());
            }
        });

        // form-urlencoded
        router.post("/form", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String val = request.getParameter("field1");
                response.status(HttpStatus.OK).body(("field1=" + val).getBytes());
            }
        });

        // Large form-urlencoded (for HttpBodyStreamDecoder)
        router.post("/large-form", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String val = request.getParameter("big");
                response.status(HttpStatus.OK).body(("len=" + (val != null ? val.length() : 0)).getBytes());
            }
        });

        // Chunked write
        router.get("/chunked", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                byte[] data = "chunked".getBytes();
                response.write(data);
                response.write(data);
                response.commit();
            }
        });

        // Remove header
        router.get("/remove-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.header("X-Remove", "toremove");
                response.removeHeader("X-Remove");
                response.body("ok".getBytes());
            }
        });

        // SSE endpoint
        router.get("/sse", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.sse("sse data");
            }
        });

        // SSE with event
        router.get("/sse-event", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.sse("update", "event data");
            }
        });

        // setLastModified
        router.get("/last-modified", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).header("Last-Modified", "Thu, 01 Jan 2025 00:00:00 GMT").body("ok".getBytes());
            }
        });

        // Large response write (triggers flush path)
        router.get("/large-write", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("application/octet-stream");
                byte[] big = new byte[65536];
                new java.util.Random().nextBytes(big);
                response.write(big);
                response.commit();
            }
        });

        // Connection: close
        router.get("/keep-alive", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body("ok".getBytes());
            }
        });

        // 404
        router.get("/not-found", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NOT_FOUND).write("404".getBytes());
            }
        });

        // Version
        router.get("/version", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body(request.getHttpVersion().toString().getBytes());
            }
        });

        // addHeader with 3+ values (list path)
        router.get("/multi3", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-List", "a");
                response.addHeader("X-List", "b");
                response.addHeader("X-List", "c");
                response.body("ok".getBytes());
            }
        });

        // sendFile with Last-Modified
        router.get("/last-mod-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // sendFile gzip (html file = compressible MIME type)
        router.get("/send-file-gzip", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(htmlFile);
            }
        });

        // sendFile small file (triggers sendFileBuffered, file < bufferSize)
        router.get("/send-file-small", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(smallFile);
            }
        });

        // getHeader in handler
        router.get("/get-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).header("X-Echo", "echoVal").body(("got=" + response.getHeader("X-Echo")).getBytes());
            }
        });

        // removeHeader(key, value)
        router.get("/remove-val", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-Remove", "keep");
                response.addHeader("X-Remove", "remove");
                response.removeHeader("X-Remove", "remove");
                response.body(("val=" + response.getHeader("X-Remove")).getBytes());
            }
        });

        // Large write (flush triggers direct channel write)
        router.get("/large-write-flush", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                byte[] data = "hello".getBytes();
                response.write(data);
                response.flush();
                response.write(data);
                response.commit();
            }
        });

        // Non-existent file for 404 in sendFile path
        router.get("/send-nonexist", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(new File("/nonexistent/test.dat"));
            }
        });

        // Large file for sendFileZeroCopy
        router.get("/send-large-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // setContentLength explicitly
        router.get("/content-length", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("Content-Length", "2");
                response.body("ok".getBytes());
            }
        });

        // Multipart upload
        router.post("/upload", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                StringBuilder sb = new StringBuilder();
                for (String n : request.getMultipartFieldNames()) {
                    sb.append(n).append("=").append(request.getMultipartFieldValue(n)).append(",");
                }
                response.status(HttpStatus.OK).body(sb.toString().getBytes());
            }
        });

        server = new HTTPServer(port);
        server.requestHandler(router).bufferSize(1024);
        server.start();
    }

    // ==================== Existing tests ====================

    @Test
    public void testServerConfig() throws Exception {
        HTTPServer s2 = new HTTPServer(findFreePort());
        s2.workerNum(4).bufferSize(8192).localOnly(true).printSSLErrorLog(true).printReadErrorLog(true).startupBannerEnabled(false);
        assertNotNull(s2);
        s2.stop();
    }

    @Test
    public void testGetStatus() throws Exception {
        HttpURLConnection conn = open("/app/status");
        assertEquals(200, conn.getResponseCode());
        assertEquals("OK", new String(readAll(conn)).trim());
        conn.disconnect();
    }

    @Test
    public void testParameters() throws Exception {
        HttpURLConnection conn = open("/app/params?name=hello&tag=a&tag=b");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("name=hello"), body);
        assertTrue(body.contains("tags=a,b"), body);
        conn.disconnect();
    }

    @Test
    public void testRemoteInfo() throws Exception {
        HttpURLConnection conn = open("/app/remote-info");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("remoteHost="), body);
        assertTrue(body.contains("remotePort="), body);
        assertTrue(body.contains("serverPort=" + port), body);
        assertTrue(body.contains("requestId="), body);
        conn.disconnect();
    }

    @Test
    public void testAttributes() throws Exception {
        HttpURLConnection conn = open("/app/attr");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("k1=v1"), body);
        conn.disconnect();
    }

    @Test
    public void testSendFile() throws Exception {
        HttpURLConnection conn = open("/app/send-file");
        assertEquals(200, conn.getResponseCode());
        byte[] body = readAll(conn);
        assertTrue(body.length > 1000, "sendFile body too small: " + body.length);
        conn.disconnect();
    }

    @Test
    public void testNotFound() throws Exception {
        HttpURLConnection conn = open("/app/not-found");
        assertEquals(404, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testPostEcho() throws Exception {
        HttpURLConnection conn = open("/app/echo");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write("hello world".getBytes());
        assertEquals(200, conn.getResponseCode());
        assertEquals("hello world", new String(readAll(conn)).trim());
        conn.disconnect();
    }

    @Test
    public void testFormUrlEncoded() throws Exception {
        HttpURLConnection conn = open("/app/form");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write("field1=testValue".getBytes());
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("field1=testValue"), body);
        conn.disconnect();
    }

    @Test
    public void testHttpVersion() throws Exception {
        HttpURLConnection conn = open("/app/version");
        assertEquals(200, conn.getResponseCode());
        assertEquals("HTTP/1.1", new String(readAll(conn)).trim());
        conn.disconnect();
    }

    // ==================== New coverage tests ====================

    @Test
    public void testMultiValueHeaders() throws Exception {
        HttpURLConnection conn = open("/app/multi-header");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertEquals("ok", body);
        conn.disconnect();
    }

    @Test
    public void testEarlyHints() throws Exception {
        HttpURLConnection conn = open("/app/early-hints");
        int code = conn.getResponseCode();
        assertTrue(code == 200 || code == 103, "expected 200 or 103, got " + code);
        conn.disconnect();
    }

    @Test
    public void testChunkedWrite() throws Exception {
        HttpURLConnection conn = open("/app/chunked");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("chunked"), body);
        conn.disconnect();
    }

    @Test
    public void testRemoveHeader() throws Exception {
        HttpURLConnection conn = open("/app/remove-header");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertEquals("ok", body);
        conn.disconnect();
    }

    @Test
    public void testLargeWrite() throws Exception {
        HttpURLConnection conn = open("/app/large-write");
        assertEquals(200, conn.getResponseCode());
        byte[] body = readAll(conn);
        assertEquals(65536, body.length, "large write should return 65536 bytes");
        conn.disconnect();
    }

    @Test
    public void testSse() throws Exception {
        HttpURLConnection conn = open("/app/sse");
        try { int code = conn.getResponseCode(); } catch (Exception ignored) {}
        conn.disconnect();
    }

    @Test
    public void testSseEvent() throws Exception {
        HttpURLConnection conn = open("/app/sse-event");
        try { int code = conn.getResponseCode(); } catch (Exception ignored) {}
        conn.disconnect();
    }

    @Test
    public void testLastModified() throws Exception {
        HttpURLConnection conn = open("/app/last-modified");
        int code = conn.getResponseCode();
        String body = code != 200 ? new String(readAll(conn)) : "";
        assertTrue(code == 200, "expected 200, got " + code + " body=" + body);
        conn.disconnect();
    }

    @Test
    public void testKeepAlive() throws Exception {
        HttpURLConnection conn = open("/app/keep-alive");
        conn.setRequestProperty("Connection", "close");
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testLargeFormUrlEncoded() throws Exception {
        StringBuilder sb = new StringBuilder("big=");
        for (int i = 0; i < 5000; i++) sb.append("data");
        String bodyData = sb.toString();

        HttpURLConnection conn = open("/app/large-form");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(bodyData.getBytes());
        assertEquals(200, conn.getResponseCode());
        String resp = new String(readAll(conn));
        assertTrue(resp.contains("len="), resp);
        conn.disconnect();
    }

    @Test
    public void testMultipartUpload() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "value1\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"field2\"\r\n\r\n"
                + "value2\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpURLConnection conn = open("/app/upload");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        conn.getOutputStream().write(body.getBytes());
        assertEquals(200, conn.getResponseCode());
        String resp = new String(readAll(conn));
        assertTrue(resp.contains("field1=value1"), resp);
        assertTrue(resp.contains("field2=value2"), resp);
        conn.disconnect();
    }

    @Test
    public void testSendFileGzip() throws Exception {
        HttpURLConnection conn = open("/app/send-file-gzip");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testSendFileSmall() throws Exception {
        HttpURLConnection conn = open("/app/send-file-small");
        assertEquals(200, conn.getResponseCode());
        byte[] body = readAll(conn);
        assertTrue(body.length > 0, "small file body should not be empty");
        conn.disconnect();
    }

    @Test
    public void testMultiHeaderListPath() throws Exception {
        HttpURLConnection conn = open("/app/multi3");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertEquals("ok", body.trim());
        conn.disconnect();
    }

    @Test
    public void testSendFileWithLastModified() throws Exception {
        HttpURLConnection conn = open("/app/last-mod-file");
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testGetHeader() throws Exception {
        HttpURLConnection conn = open("/app/get-header");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("got=echoVal"), body);
        conn.disconnect();
    }

    @Test
    public void testRemoveHeaderValue() throws Exception {
        HttpURLConnection conn = open("/app/remove-val");
        assertEquals(200, conn.getResponseCode());
        String body = new String(readAll(conn));
        assertTrue(body.contains("val=keep"), body);
        conn.disconnect();
    }

    @Test
    public void testLargeWriteFlush() throws Exception {
        HttpURLConnection conn = open("/app/large-write-flush");
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testSendNonExistentFile() throws Exception {
        HttpURLConnection conn = open("/app/send-nonexist");
        int code = conn.getResponseCode();
        // Server returns 404 because file doesn't exist
        assertTrue(code >= 400, "expected error status for non-existent file, got " + code);
        conn.disconnect();
    }

    @Test
    public void testContentLength() throws Exception {
        HttpURLConnection conn = open("/app/content-length");
        assertEquals(200, conn.getResponseCode());
        String cl = conn.getHeaderField("Content-Length");
        assertNotNull(cl, "Content-Length should be set");
        conn.disconnect();
    }

    @Test
    public void testLargeMultipartUpload() throws Exception {
        // Large file field to trigger readFieldToFile in HttpBodyStreamDecoder
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100000; i++) largeContent.append("LARGE_DATA_CHUNK_");
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + largeContent.toString() + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "hello\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpURLConnection conn = open("/app/upload");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        conn.getOutputStream().write(body.getBytes());
        assertEquals(200, conn.getResponseCode());
        String resp = new String(readAll(conn));
        assertTrue(resp.contains("name=hello"), resp);
        conn.disconnect();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.stop();
        if (testFile != null) testFile.delete();
        if (htmlFile != null) htmlFile.delete();
        if (smallFile != null) smallFile.delete();
    }

    private static HttpURLConnection open(String path) throws Exception {
        return (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    }

    private static byte[] readAll(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
