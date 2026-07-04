package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

import java.util.Map;

/**
 * Route handler.
 *
 * @author wangyc
 */
public abstract class HttpRoute {

    /**
     * Handle HTTP request.
     *
     * @param path     matched subPath
     * @param request  HTTP request
     * @param response HTTP response
     * @throws Throwable if handling fails
     */
    public abstract void handle(String path, HttpRequest request, HttpResponse response) throws Throwable;

    /**
     * Default implementation delegates to handle(path, request, response).
     */
    public final void handle(HttpRequest request, HttpResponse response) throws Throwable {
        handle(request.getRequestUri(), request, response);
    }

    /**
     * Return self.
     */
    protected final HttpRoute self() {
        return this;
    }

    /**
     * Get route information for monitoring.
     * Subclasses can override to provide specific details.
     *
     * @return route information map
     */
    public Map<String, Object> getRouteInfo() {
        return null;
    }
}