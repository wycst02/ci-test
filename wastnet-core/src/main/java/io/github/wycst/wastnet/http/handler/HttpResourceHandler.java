package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;

import java.io.File;

/**
 * Static resource handler.
 *
 * @author wangyc
 */
public class HttpResourceHandler extends HttpRoute {

    private final String docBase;
    final String routePath;  // package-private for HttpRouterHandler
    private final int filePathOffset;
    private final File defaultFile;
    private final byte[] notFoundBytes;
    private byte[] notAllowedBytes;
    private boolean strictMode = true;
    private boolean cacheEnabled = true;
    private String[] earlyHintLinks;
    private String basePath;  // context path for $base_path replacement in early hints

    /**
     * Create a resource handler serving files from {@code docBase} at route {@code "/"}.
     *
     * @param docBase the root directory for static files
     */
    public HttpResourceHandler(String docBase) {
        this("/", docBase, findDefaultFile(docBase, "index.html", "index.htm"));
    }

    /**
     * Create a resource handler with a custom default file.
     *
     * @param docBase      the root directory for static files
     * @param defaultFile  fallback file for root requests (index.html equivalent)
     */
    public HttpResourceHandler(String docBase, File defaultFile) {
        this("/", docBase, defaultFile);
    }

    /**
     * Create a resource handler at a custom route path.
     *
     * @param routePath the URL path prefix for this handler (e.g. {@code "/static"})
     * @param docBase   the root directory for static files
     */
    public HttpResourceHandler(String routePath, String docBase) {
        this(routePath, docBase, findDefaultFile(docBase, "index.html", "index.htm"));
    }

    /**
     * Full constructor for a resource handler.
     *
     * @param routePath    the URL path prefix for this handler
     * @param docBase      the root directory for static files
     * @param defaultFile  fallback file for root requests, or {@code null} to disable
     */
    public HttpResourceHandler(String routePath, String docBase, File defaultFile) {
        this.routePath = routePath;
        this.filePathOffset = routePath.length();
        this.docBase = docBase;
        this.defaultFile = defaultFile;
        this.notFoundBytes = "404 Not Found".getBytes();
        this.notAllowedBytes = HttpStatus.METHOD_NOT_ALLOWED.text.getBytes();
    }

    /**
     * Set the base path for {@code $base_path} placeholder substitution in early hint links.
     * Called by HttpRouterHandler to pass the context path.
     */
    HttpResourceHandler setBasePath(String basePath) {
        this.basePath = basePath;
        resolveEarlyHintBase();
        return this;
    }

    private void resolveEarlyHintBase() {
        if (earlyHintLinks == null || basePath == null) return;
        for (int i = 0; i < earlyHintLinks.length; i++) {
            earlyHintLinks[i] = earlyHintLinks[i].replace("$base_path", basePath);
        }
    }

    /**
     * Set custom response body for 405 Method Not Allowed.
     */
    public HttpResourceHandler notAllowedBody(String body) {
        this.notAllowedBytes = body.getBytes();
        return this;
    }

    /**
     * Enable or disable HTTP cache negotiation (304 Not Modified / ETag / Last-Modified).
     * <p>
     * Default is {@code true}. When disabled, the server always serves the full file
     * without checking {@code If-None-Match} or {@code If-Modified-Since} headers.
     */
    public HttpResourceHandler cacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
        return this;
    }

    /**
     * Disable strict mode: allow all HTTP methods (GET, POST, etc.).
     * <p>
     * By default, only GET is allowed; other methods return 405 Method Not Allowed.
     */
    public HttpResourceHandler allowAllMethods() {
        this.strictMode = false;
        return this;
    }

    /**
     * Configure 103 Early Hints (RFC 8297) for the default page (index.html).
     * <p>
     * When the default file (index.html) is requested, the server sends 103 Early Hints
     * with the specified Link headers before the final response, allowing the client
     * to preload static resources while the server processes the request.
     * <p>
     * The placeholder {@code $base_path} in each link header is replaced with the
     * resource handler's base path at configuration time.
     * <p>
     * Example:
     * <pre>
     * new HttpResourceHandler("/my-app", docBase)
     *     .earlyHints("&lt;$base_path/style.css&gt;; rel=preload; as=style");
     * // → &lt;/my-app/style.css&gt;; rel=preload; as=style
     * </pre>
     *
     * @param linkHeaders one or more Link header values
     * @return this handler for chaining
     */
    public HttpResourceHandler earlyHints(String... linkHeaders) {
        if (linkHeaders != null && linkHeaders.length > 0) {
            String[] list = new String[linkHeaders.length];
            int count = 0;
            for (String link : linkHeaders) {
                if (link != null && !link.isEmpty()) {
                    list[count++] = link;
                }
            }
            this.earlyHintLinks = count == 0 ? null : count == linkHeaders.length ? list : java.util.Arrays.copyOf(list, count);
            resolveEarlyHintBase();
        } else {
            this.earlyHintLinks = null;
        }
        return this;
    }

    private void sendEarlyHints(HttpResponse response) throws Throwable {
        for (String link : earlyHintLinks) {
            response.earlyHints(link);
        }
    }

    private static File findDefaultFile(String docBase, String... defaultFiles) {
        for (String file : defaultFiles) {
            File f = new File(docBase, file);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        if (strictMode && request.getMethod() != HttpMethod.GET) {
            response.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaderNormalized.getAllow(), "GET, HEAD")
                    .body(notAllowedBytes);
            return;
        }
        File file;
        int len = path.length() - filePathOffset;
        if (len <= 1) {
            file = defaultFile;
        } else {
            int filePathOffset = this.filePathOffset, ch;
            while ((ch = path.charAt(filePathOffset)) == '/' || ch == '\\') {
                ++filePathOffset;
            }
            String rp = path.substring(filePathOffset);
            if (rp.contains("../")) { // Security: prevent path traversal, rp is decoded path
                response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
                return;
            }
            file = new File(docBase, rp);
        }

        if (file == null || !file.isFile()) {
            response.status(HttpStatus.NOT_FOUND).write(notFoundBytes);
            return;
        }

        // Send 103 Early Hints when serving the default index page
        if (earlyHintLinks != null && file.equals(defaultFile)) {
            sendEarlyHints(response);
        }

        response.sendFile(file, cacheEnabled, -1);
    }
}