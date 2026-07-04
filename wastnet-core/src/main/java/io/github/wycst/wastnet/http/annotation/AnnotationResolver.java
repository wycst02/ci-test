package io.github.wycst.wastnet.http.annotation;

import java.util.List;

/**
 * Pluggable strategy for resolving annotations to route and component metadata,
 * also acting as a scanner filter to avoid loading irrelevant classes.
 * <p>
 * The default implementation resolves framework annotations ({@code @Controller},
 * {@code @Endpoint}, {@code @Sse}, {@code @WebSocket}, {@code @Component}).
 * Users may provide a custom implementation to bridge third-party annotations
 * (e.g. Spring Boot's {@code @RestController}, {@code @RequestMapping}, {@code @Service}).
 *
 * @author wangyc
 */
public interface AnnotationResolver extends AnnotationFilter {

    /**
     * Returns {@code true} if the class is a request-handling controller.
     */
    boolean isController(Class<?> clazz);

    /**
     * Extract the base path from a controller class annotation.
     */
    String resolveControllerPath(Class<?> clazz);

    /**
     * Extract all endpoint route definitions from a controller class.
     */
    List<MethodRouteInfo> resolveEndpointRoutes(Class<?> clazz);

    /**
     * Extract all SSE endpoint definitions from a controller class.
     */
    List<MethodRouteInfo> resolveSseEndpoints(Class<?> clazz);

    /**
     * Returns {@code true} if the class is a WebSocket endpoint.
     */
    boolean isWebSocketEndpoint(Class<?> clazz);

    /**
     * Extract the WebSocket endpoint path from a class annotation.
     */
    String resolveWebSocketPath(Class<?> clazz);

    /**
     * Returns {@code true} if the class is a managed component.
     */
    boolean isComponent(Class<?> clazz);

    /**
     * Extract the component/bean name from a component class annotation.
     */
    String resolveComponentName(Class<?> clazz);
}
