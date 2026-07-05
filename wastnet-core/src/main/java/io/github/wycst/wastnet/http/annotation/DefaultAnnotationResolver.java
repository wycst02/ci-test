package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation that resolves the framework's built-in annotations:
 * {@link Controller @Controller}, {@link Endpoint @Endpoint},
 * {@link Sse @Sse}, {@link WebSocket @WebSocket},
 * {@link Component @Component}.
 *
 * @author wangyc
 */
public class DefaultAnnotationResolver implements AnnotationResolver {

    @Override
    public boolean accept(Class<?> clazz) {
        return isComponent(clazz) || isController(clazz) || isWebSocketEndpoint(clazz)
                || clazz.isAnnotationPresent(Configuration.class);
    }

    @Override
    public boolean isController(Class<?> clazz) {
        return clazz.isAnnotationPresent(Controller.class);
    }

    @Override
    public String resolveControllerPath(Class<?> clazz) {
        Controller ann = clazz.getAnnotation(Controller.class);
        return ann != null ? ann.value() : "";
    }

    @Override
    public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> clazz) {
        List<MethodRouteInfo> routes = new ArrayList<MethodRouteInfo>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Endpoint ann = method.getAnnotation(Endpoint.class);
            if (ann != null && !ann.value().isEmpty()) {
                routes.add(new MethodRouteInfo(ann.value(), ann.allowMethods(), method, Endpoint.class));
            }
        }
        return routes;
    }

    @Override
    public List<MethodRouteInfo> resolveSseEndpoints(Class<?> clazz) {
        List<MethodRouteInfo> endpoints = new ArrayList<MethodRouteInfo>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Sse ann = method.getAnnotation(Sse.class);
            if (ann != null && !ann.value().isEmpty()) {
                endpoints.add(new MethodRouteInfo(ann.value(), new HttpMethod[0], method, Sse.class));
            }
        }
        return endpoints;
    }

    @Override
    public boolean isWebSocketEndpoint(Class<?> clazz) {
        return clazz.isAnnotationPresent(WebSocket.class);
    }

    @Override
    public String resolveWebSocketPath(Class<?> clazz) {
        WebSocket ann = clazz.getAnnotation(WebSocket.class);
        return ann != null && !ann.value().isEmpty() ? ann.value() : "";
    }

    @Override
    public boolean isComponent(Class<?> clazz) {
        return clazz.isAnnotationPresent(Component.class);
    }

    @Override
    public String resolveComponentName(Class<?> clazz) {
        Component ann = clazz.getAnnotation(Component.class);
        if (ann != null && !ann.value().isEmpty()) {
            return ann.value();
        }
        String simpleName = clazz.getSimpleName();
        if (simpleName.length() > 0) {
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }
        return simpleName;
    }
}
