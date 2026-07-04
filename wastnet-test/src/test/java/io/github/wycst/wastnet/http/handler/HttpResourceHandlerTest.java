package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpResourceHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Unit tests for {@link HttpResourceHandler}.
 *
 * @author wangyc
 */
public class HttpResourceHandlerTest {

    // ==================== Path traversal protection ====================

    @Test
    public void testHandleRejectsPathTraversal() throws Throwable {
        // Create a resource handler with a temp directory
        File tempDir = createTempDir("res-");
        try {
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/../etc/passwd", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleRejectsEncodedPathTraversal() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/..\\..\\etc\\passwd", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleReturns405ForNonGetInStrictMode() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.POST),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.METHOD_NOT_ALLOWED, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleReturns404ForNonexistentFile() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/nonexistent.html", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.NOT_FOUND, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleServesExistingFile() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            // Create an actual file to serve
            File testFile = new File(tempDir, "test.txt");
            java.nio.file.Files.write(testFile.toPath(), "hello".getBytes());
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/test.txt", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    public void testHandleServesIndexHtmlByDefault() throws Throwable {
        File tempDir = createTempDir("res-");
        try {
            File indexFile = new File(tempDir, "index.html");
            java.nio.file.Files.write(indexFile.toPath(), "<html></html>".getBytes());
            HttpResourceHandler handler = new HttpResourceHandler("/", tempDir.getAbsolutePath());
            final HttpStatus[] capturedStatus = {null};
            handler.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET),
                    MockHttpTestBase.mockResponse(capturedStatus, (String[]) null, null));
            Assertions.assertEquals(HttpStatus.OK, capturedStatus[0]);
        } finally {
            deleteDir(tempDir);
        }
    }

    // ==================== earlyHints builder ====================

    @Test
    public void testEarlyHintsNullInput() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints((String[]) null);
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsEmptyArray() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints();
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsArrayWithEmptyString() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints("");
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsValidLinks() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints("<a.css>; rel=preload", "<b.js>; rel=preload");
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<a.css>; rel=preload", links[0]);
        Assertions.assertEquals("<b.js>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsFiltersNullAndEmptyEntries() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints("<valid.css>; rel=preload", null, "", "<also.css>; rel=preload");
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertNotNull(links);
        Assertions.assertEquals(2, links.length);
        Assertions.assertEquals("<valid.css>; rel=preload", links[0]);
        Assertions.assertEquals("<also.css>; rel=preload", links[1]);
    }

    @Test
    public void testEarlyHintsAllEntriesNullResultsInNull() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints(null, "", null);
        Assertions.assertNull(getEarlyHintLinks(handler));
    }

    @Test
    public void testEarlyHintsWithoutBasePathLeavesDollarPlaceholder() {
        HttpResourceHandler handler = new HttpResourceHandler("/", ".");
        handler.earlyHints("<style.css>; rel=preload");
        // no setBasePath → resolveEarlyHintBase returns early (earlyHintLinks != null but basePath == null)
        String[] links = getEarlyHintLinks(handler);
        Assertions.assertEquals("<style.css>; rel=preload", links[0]);
    }

    private static String[] getEarlyHintLinks(HttpResourceHandler handler) {
        try {
            Field field = HttpResourceHandler.class.getDeclaredField("earlyHintLinks");
            field.setAccessible(true);
            return (String[]) field.get(handler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File createTempDir(String prefix) {
        File dir = new File(System.getProperty("java.io.tmpdir"), prefix + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    private static void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }
}
