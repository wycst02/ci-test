/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;
import io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Router Request Handler with context path support and handler dispatching.
 * <p>
 * Extends {@link DefaultUpgradeHandler} to support WebSocket and h2c upgrade registration
 * with automatic context path prefix.
 *
 * <p>contextPath: default is "/"; if configured, must start with "/" and trailing "/" will be removed.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>exactRoute: exact match (no regex)</li>
 *   <li>route: prefix match by default, e.g. "/api" matches "/api", "/api/xxx"</li>
 *   <li>route with "^" prefix: treated as regex</li>
 *   <li>route with "$" suffix: exact regex match</li>
 *   <li>route without "^" or "$": prefix match (automatically appends ".*")</li>
 *   <li>ws(): register WebSocket with context path prefix</li>
 *   <li>h2c(): register h2c with context path prefix</li>
 * </ul>
 *
 * @author wangyc
 */
public class HttpRouterHandler extends DefaultUpgradeHandler 
        implements HttpRequestHandler, ClearableHandler {

    /**
     * Default health check route path
     */
    public static final String DEFAULT_HEALTH_ROUTE = "/health";

    /**
     * Server start time (milliseconds since epoch)
     */
    private final long startTime = System.currentTimeMillis();

    private final String contextPath;
    private String healthRoute = DEFAULT_HEALTH_ROUTE;
    private final int contextPathLen;
    // exactRoutes is rarely seen
    private final Map<String, HttpRoute> exactRoutes = new HashMap<String, HttpRoute>();
    private final List<RouteEntry> routes = new ArrayList<RouteEntry>();
    private HttpRequestHandler notFoundHandler;
    private byte[] notFoundBytes;
    private boolean autoRedirect = true;

    /**
     * Create a router handler with root context path ({@code "/"}).
     */
    public HttpRouterHandler() {
        this("/");
    }

    /**
     * Create a router handler with the given context path.
     * <p>
     * The context path must start with {@code "/"}; trailing slashes are removed.
     * All routes registered via {@link #route(String, HttpRoute)} and friends
     * will be prefixed with this context path at match time.
     *
     * @param contextPath the context path (e.g. {@code "/api"}), defaults to {@code "/"}
     */
    public HttpRouterHandler(String contextPath) {
        if (contextPath == null || (contextPath = contextPath.trim()).isEmpty()) {
            contextPath = "/";
        } else {
            if (!contextPath.startsWith("/")) {
                contextPath = "/" + contextPath;
            }
            // Remove all trailing "/"
            int end = contextPath.length();
            while (end > 1 && contextPath.charAt(end - 1) == '/') {
                --end;
            }
            contextPath = contextPath.substring(0, end);
        }
        this.contextPath = contextPath;
        this.contextPathLen = contextPath.length();
    }

    /**
     * Register an exact-matched route (no regex).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler exactRoute(String path, HttpRoute route) {
        exactRoutes.put(path, route.self());
        return this;
    }

    /**
     * Register an exact-matched route restricted to the given HTTP methods.
     *
     * @param path          the exact path
     * @param route         the route handler
     * @param allowMethods  allowed HTTP methods (empty means no restriction, equivalent to {@link #exactRoute(String, HttpRoute)})
     * @return this handler for chaining
     */
    public HttpRouterHandler exactRoute(String path, HttpRoute route, HttpMethod... allowMethods) {
        exactRoutes.put(path, allowMethods.length == 0 ? route.self() : new HttpMethodRoute(route, allowMethods));
        return this;
    }

    /**
     * Register a prefix-matched route.
     * <p>
     * By default, the path is treated as a prefix: {@code "/api"} matches both
     * {@code "/api"} and {@code "/api/xxx"}. Prefix a path with {@code "^"} to
     * treat it as a regex pattern.
     *
     * @param path  the path prefix or regex pattern
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler route(String path, HttpRoute route) {
        routes.add(new RouteEntry(path, route.self()));
        return this;
    }

    /**
     * Register a prefix-matched route with optional method restrictions.
     */
    public HttpRouterHandler route(String path, HttpRoute route, HttpMethod... allowMethods) {
        routes.add(new RouteEntry(path, allowMethods.length == 0 ? route.self() : new HttpMethodRoute(route, allowMethods)));
        return this;
    }

    /**
     * Register a GET route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler get(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.GET));
        return this;
    }

    /**
     * Register a POST route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler post(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.POST));
        return this;
    }

    /**
     * Register a PUT route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler put(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.PUT));
        return this;
    }

    /**
     * Register a DELETE route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler delete(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.DELETE));
        return this;
    }

    /**
     * Register a PATCH route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler patch(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.PATCH));
        return this;
    }

    /**
     * Register a reverse proxy route (default: path rewrite disabled).
     *
     * @param path  the request path to match
     * @param route the target URI
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, String route) {
        return proxy(path, route, false);
    }

    /**
     * Register a reverse proxy route with optional path rewrite.
     *
     * @param path    the request path to match
     * @param route   the target URI
     * @param rewrite whether to rewrite the request path when proxying
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, String route, boolean rewrite) {
        return proxy(path, HttpProxyConfig.target(route).rewrite(rewrite));
    }

    /**
     * Register a reverse proxy route with a full {@link HttpProxyConfig}.
     *
     * @param path   the request path to match
     * @param config the proxy configuration
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, HttpProxyConfig config) {
        routes.add(new RouteEntry(path, new HttpProxyRoute(config)));
        return this;
    }

    /**
     * Register a static resources handler.
     *
     * @param resource the resource handler (configured with base path, directory, etc.)
     * @return this handler for chaining
     */
    public HttpRouterHandler resource(HttpResourceHandler resource) {
        routes.add(new RouteEntry(resource.routePath, true, resource));
        resource.setBasePath(contextPath);
        return this;
    }

    /**
     * Set a custom handler for unmatched requests (404 fallback).
     *
     * @param notFoundHandler the fallback handler; {@code null} to restore default
     * @return this handler for chaining
     */
    public HttpRouterHandler notFoundHandler(HttpRequestHandler notFoundHandler) {
        this.notFoundHandler = notFoundHandler;
        return this;
    }

    /**
     * Set the health check route path.
     * Set to null or empty to disable health endpoint.
     *
     * @param healthRoute the health route path (e.g., "/health")
     * @return this handler for chaining
     */
    public HttpRouterHandler healthRoute(String healthRoute) {
        if (healthRoute == null || healthRoute.trim().isEmpty()) {
            this.healthRoute = null;
        } else {
            if (!healthRoute.startsWith("/")) {
                healthRoute = "/" + healthRoute;
            }
            this.healthRoute = healthRoute;
        }
        return this;
    }

    /**
     * Enable or disable automatic redirect from root path "/" to context path.
     * When enabled (default), accessing "/" will redirect to "contextPath/".
     * When disabled, accessing "/" will result in 404 if no route matches.
     *
     * @param enable true to enable redirect, false to disable
     * @return this handler for chaining
     */
    public HttpRouterHandler autoRedirect(boolean enable) {
        this.autoRedirect = enable;
        return this;
    }

    /**
     * Clear all registered routes and releases associated resources.
     */
    public void clear() {
        for (HttpRoute route : exactRoutes.values()) {
            if (route instanceof ClearableHandler) {
                ((ClearableHandler) route).clear();
            }
        }
        for (RouteEntry entry : routes) {
            if (entry.handler instanceof ClearableHandler) {
                ((ClearableHandler) entry.handler).clear();
            }
        }
        exactRoutes.clear();
        routes.clear();
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        String path = request.getRequestUri();

        // Extract subPath
        String subPath;
        // Context path matching
        if (contextPathLen == 1) {
            subPath = path;
        } else {
            if (!path.startsWith(contextPath) || (path.length() > contextPathLen && path.charAt(contextPathLen) != '/')) {
                // Check if root path "/" and redirect to context path
                if (autoRedirect && "/".equals(path) && contextPathLen > 1) {
                    response.status(HttpStatus.MOVED_PERMANENTLY)
                            .header("Location", contextPath + "/")
                            .commit();
                    return;
                }
                // Not matched or Invalid path format after contextPath
                handleNotFound(request, response);
                return;
            }
            subPath = path.substring(contextPathLen);
            if (subPath.isEmpty()) {
                subPath = "/";
            }
        }
        // Exact match (health route should have lower priority than exact routes)
        if (!exactRoutes.isEmpty()) {
            // Exact match first
            HttpRoute exactRoute = exactRoutes.get(subPath);
            if (exactRoute != null) {
                exactRoute.handle(subPath, request, response);
                return;
            }
        }

        // Health route check (before prefix/regex match)
        if (subPath.equals(healthRoute)) {
            response.setHeader("Content-Type", "application/json; charset=utf-8");
            response.status(HttpStatus.OK).body("{\"status\":\"UP\"}".getBytes(Utils.UTF_8));
            return;
        }

        // Regex/prefix match
        for (RouteEntry entry : routes) {
            if (entry.match(subPath)) {
                entry.handler.handle(subPath, request, response);
                return;
            }
        }

        handleNotFound(request, response);
    }

    private void handleNotFound(HttpRequest request, HttpResponse response) throws Throwable {
        if (notFoundHandler != null) {
            notFoundHandler.handle(request, response);
        } else {
            if (notFoundBytes == null) {
                notFoundBytes = "404 Not Found".getBytes();
            }
            response.status(HttpStatus.NOT_FOUND).body(notFoundBytes);
        }
    }

    /**
     * Return the context path configured for this router.
     *
     * @return the context path, never {@code null} (default is {@code "/"})
     */
    public String getContextPath() {
        return contextPath;
    }

    /**
     * Get router statistics for monitoring.
     *
     * @param request HTTP request (query params can control output: detail, system)
     * @return router information map containing context path and route details
     */
    public Map<String, Object> getRouterStats(HttpRequest request) {
        Map<String, Object> stats = new HashMap<String, Object>();
        stats.put("startAt", startTime);
        stats.put("uptime", System.currentTimeMillis() - startTime);
        stats.put("contextPath", contextPath);
        stats.put("exactRouteCount", exactRoutes.size());
        stats.put("routeCount", routes.size());

        // Check query params for optional info
        String query = request.getQueryString();
        boolean includeSystem = query == null || query.contains("system");

        if (includeSystem) {
            // System info
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> system = new HashMap<String, Object>();
            system.put("totalMemory", runtime.totalMemory());
            system.put("freeMemory", runtime.freeMemory());
            system.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
            system.put("maxMemory", runtime.maxMemory());
            system.put("availableProcessors", runtime.availableProcessors());
            stats.put("system", system);
        }

        // Route info (included by default)
        List<Map<String, Object>> routeList = new ArrayList<Map<String, Object>>();

        // Exact routes
        for (Map.Entry<String, HttpRoute> entry : exactRoutes.entrySet()) {
            Map<String, Object> routeInfo = new HashMap<String, Object>();
            routeInfo.put("path", entry.getKey());
            routeInfo.put("type", "exact");
            Map<String, Object> handlerInfo = entry.getValue().getRouteInfo();
            if (handlerInfo != null) {
                routeInfo.put("handler", handlerInfo);
            }
            routeList.add(routeInfo);
        }

        // Prefix/regex routes
        for (RouteEntry entry : routes) {
            Map<String, Object> routeInfo = new HashMap<String, Object>();
            routeInfo.put("path", entry.pattern);
            routeInfo.put("type", entry.prefix ? "prefix" : "regex");
            Map<String, Object> handlerInfo = entry.handler.getRouteInfo();
            if (handlerInfo != null) {
                routeInfo.put("handler", handlerInfo);
            }
            routeList.add(routeInfo);
        }

        stats.put("routes", routeList);
        return stats;
    }

    /**
     * Route entry with pattern matching.
     */
    private static class RouteEntry {
        private final String pattern;
        private final boolean prefix;
        private final Pattern regex;
        final HttpRoute handler;

        RouteEntry(String pattern, HttpRoute handler) {
            this(pattern, false, handler);
        }

        RouteEntry(String pattern, boolean prefix, HttpRoute handler) {
            prefix = prefix || pattern.charAt(0) != '^';
            this.pattern = pattern;
            this.handler = handler;
            this.regex = prefix ? null : Pattern.compile(pattern.endsWith("$") ? pattern : pattern + ".*");
            this.prefix = prefix;
        }

        boolean match(String path) {
            if (prefix) {
                return path.startsWith(pattern);
            }
            return regex.matcher(path).matches();
        }
    }

    // ==================== Upgrade registration ====================

    /**
     * Build full path by appending path to contextPath.
     */
    private String buildFullPath(String path) {
        if (contextPathLen == 1) {
            return path.startsWith("/") ? path : "/" + path;
        }
        return contextPath + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Register a WebSocket resource with context path prefix.
     *
     * @param path    WebSocket path (will be prefixed with contextPath)
     * @param resource WebSocket resource
     * @return WebSocketResource
     */
    @Override
    public WebSocketResource ws(String path, WebSocketResource resource) {
        return super.ws(buildFullPath(path), resource);
    }

    /**
     * Register an h2c resource with context path prefix.
     *
     * @param path h2c path (will be prefixed with contextPath)
     */
    @Override
    public void h2c(String path) {
        super.h2c(buildFullPath(path));
    }

    /**
     * Register a Server-Sent Events endpoint with default timeout (30 minutes).
     * <p>
     * The handler receives a ready-to-use {@link SseEmitter} with SSE headers already sent.
     * Framework manages the lifecycle: handler runs asynchronously, the request thread blocks
     * until {@link SseEmitter#close()} is called or the 30-minute timeout elapses.
     * <p>
     * Use {@link #sse(String, long, SseHandler)} to specify a custom timeout.
     * <p>
     * Usage:
     * <pre>{@code
     * router.sse("/events", emitter -> {
     *     executor.submit(() -> {
     *         emitter.emit("hello");
     *         emitter.close();
     *     });
     * });
     * }</pre>
     *
     * @param path    the exact path for SSE endpoint
     * @param handler the SSE handler
     * @return this router handler for chaining
     */
    public HttpRouterHandler sse(String path, SseHandler handler) {
        return sse(path, HttpConf.SSE_TIMEOUT_MS, handler);
    }

    /**
     * Register a Server-Sent Events endpoint with a custom timeout.
     * <p>
     * The handler runs asynchronously; the request thread blocks until
     * {@link SseEmitter#close()} is called or the timeout elapses.
     *
     * @param path      the exact path for SSE endpoint
     * @param timeoutMs idle timeout in milliseconds
     * @param handler   the SSE handler
     * @return this router handler for chaining
     */
    public HttpRouterHandler sse(String path, long timeoutMs, SseHandler handler) {
        exactRoutes.put(path, new HttpRoute() {
            @Override
            public void handle(String subPath, HttpRequest request, HttpResponse response) throws Throwable {
                final SseEmitter emitter = ((HttpCompleteResponse) response).sseEmitter();
                final ChannelContext sseCtx = ((HttpInternalRequest) request).ctx();
                sseCtx.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handler.handle(emitter);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                if (!emitter.awaitClose(timeoutMs)) {
                    emitter.close(); // timeout: force close
                }
            }
        });
        return this;
    }
}