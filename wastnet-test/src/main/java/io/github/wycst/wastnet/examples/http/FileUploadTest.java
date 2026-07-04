package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Set;

/**
 * Large file upload test example.
 * Demonstrates streaming upload of large files via multipart/form-data.
 * 
 * Usage:
 * 1. Start this server
 * 2. Upload file using curl:
 *    curl -X POST -F "file=@/path/to/largefile.zip" http://localhost:8080/upload
 * 
 * Or use HTML form:
 * <form action="http://localhost:8080/upload" method="post" enctype="multipart/form-data">
 *   <input type="file" name="file" />
 *   <input type="submit" value="Upload" />
 * </form>
 */
public class FileUploadTest {

    private static final Log log = LogFactory.getLog(FileUploadTest.class);
    
    // Target directory for uploaded files
    private static final String UPLOAD_DIR = "e:/tmp/wastnet-upload-tmp";

    public static void main(String[] args) throws Exception {
//        System.setProperty("wastnet.http.body-max-size", "1001");

        // Ensure upload directory exists
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();

        HTTPServer httpServer = HTTPServer.of(8080, nioConfig)
                .sslContext(createSslContext())
                .h2()
                .printReadErrorLog(true)
                .printStackTraceError(true)
                .requestHandler(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        String uri = request.getRequestUri();
                        System.out.println("uri " + uri);
                        
                        // Handle upload endpoint
                        if ("/upload".equals(uri) && request.getMethod() == HttpMethod.POST) {
                            handleUpload(request, response);
                            return;
                        }
                        
                        // Default: show upload form
                        showUploadForm(response);
                    }
                }).start();
        
        log.info("File upload server started at https://localhost:{}", httpServer.getPort());
        log.info("Upload directory: {}", UPLOAD_DIR);
    }

    /**
     * Handle file upload request.
     */
    private static void handleUpload(HttpRequest request, HttpResponse response) throws Exception {
        long startTime = System.currentTimeMillis();
        if (!request.isMultipart()) {
            System.out.println("================400 status ");
            response.status(400).body("Content-Type must be multipart/form-data");
            return;
        }

        Set<String> fieldNames = request.getMultipartFieldNames();
        System.out.println("fieldNames: " + fieldNames);
        StringBuilder result = new StringBuilder();
        result.append("Upload result:\n");

        for (String fieldName : fieldNames) {
            MultipartField field = request.getMultipartField(fieldName);

            if (field.isFile()) {
                // File upload field - save to disk
                long fieldStart = System.currentTimeMillis();
                String originalName = field.getFileName();
                File targetFile = new File(UPLOAD_DIR, originalName);

                // Use transferTo for efficient file copy (streaming, no memory overhead)
                field.transferTo(targetFile);
                long fieldEnd = System.currentTimeMillis();

                result.append("  File: ").append(originalName)
                      .append(" -> ").append(targetFile.getAbsolutePath())
                      .append(" (").append(targetFile.length()).append(" bytes, ")
                      .append(fieldEnd - fieldStart).append("ms)\n");

                log.info("File saved: {} -> {}, size: {}, time: {}ms", originalName, targetFile.getAbsolutePath(), targetFile.length(), fieldEnd - fieldStart);
            } else {
                // Regular form field
                String value = request.getMultipartFieldValue(fieldName);
                result.append("  Field: ").append(fieldName).append(" = ").append(value).append("\n");
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        result.append("Total time: ").append(totalTime).append("ms\n");
        log.info("Upload completed, total time: {}ms", totalTime);

        response.contentType("text/plain;charset=utf-8")
                .body(result.toString());
    }

    /**
     * Show HTML upload form.
     */
    private static void showUploadForm(HttpResponse response) {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head><title>File Upload</title></head>\n" +
                "<body>\n" +
                "<h1>File Upload Test</h1>\n" +
                "<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">\n" +
                "  <p>File 1: <input type=\"file\" name=\"file1\" /></p>\n" +
                "  <p>File 2: <input type=\"file\" name=\"file2\" /></p>\n" +
                "  <p>File 3: <input type=\"file\" name=\"file3\" /></p>\n" +
                "  <p>File 4: <input type=\"file\" name=\"file4\" /></p>\n" +
                "  <p>Description: <input type=\"text\" name=\"description\" /></p>\n" +
                "  <p><input type=\"submit\" value=\"Upload\" /></p>\n" +
                "</form>\n" +
                "</body>\n" +
                "</html>";
        
        response.contentType("text/html;charset=utf-8")
                .body(html);
    }

    /**
     * Generate unique filename to avoid conflicts.
     */
    private static String generateFileName(String originalName) {
        if (originalName == null || originalName.isEmpty()) {
            return "upload_" + System.currentTimeMillis();
        }
        
        // Keep original extension
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            String name = originalName.substring(0, dotIndex);
            String ext = originalName.substring(dotIndex);
            return name + "_" + System.currentTimeMillis() + ext;
        }
        
        return originalName + "_" + System.currentTimeMillis();
    }

    private static SSLContext createSslContext() throws Exception {
        // SSL
        char[] password = "123456".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");

        // keytool -genkey -alias aliastest -keyalg RSA -keysize 1024 -keypass 123456 -validity 365 -keystore server.keystore -storepass 123456
        InputStream in = FileUploadTest.class.getResourceAsStream("/server.keystore");
        keyStore.load(in, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
//        sslContext = SSLContext.getInstance("SSL");
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }
}
