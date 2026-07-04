package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Holds information about a single endpoint route parsed from a method annotation.
 *
 * @author wangyc
 */
public class MethodRouteInfo {
    private final String path;
    private final HttpMethod[] httpMethods;
    private final Method method;
    private final Class<? extends Annotation> annotationType;

    /**
     * @param path         endpoint path (e.g. "/users/{id}")
     * @param httpMethods  HTTP methods this route matches
     * @param method       the Java method implementing this endpoint
     */
    public MethodRouteInfo(String path, HttpMethod[] httpMethods, Method method) {
        this(path, httpMethods, method, null);
    }

    /**
     * @param path          endpoint path (e.g. "/users/{id}")
     * @param httpMethods   HTTP methods this route matches
     * @param method        the Java method implementing this endpoint
     * @param annotationType the annotation type that registered this route
     *                       (e.g. {@code Endpoint.class} or {@code Sse.class}); may be {@code null}
     */
    public MethodRouteInfo(String path, HttpMethod[] httpMethods, Method method, Class<? extends Annotation> annotationType) {
        this.path = path;
        this.httpMethods = httpMethods;
        this.method = method;
        this.annotationType = annotationType;
    }

    /** @return the endpoint path */
    public String getPath() {
        return path;
    }

    /** @return the HTTP methods this route matches */
    public HttpMethod[] getHttpMethods() {
        return httpMethods;
    }

    /** @return the Java method implementing this endpoint */
    public Method getMethod() {
        return method;
    }

    /**
     * @return the annotation type that registered this route,
     *         or {@code null} if not tracked
     */
    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }
}
