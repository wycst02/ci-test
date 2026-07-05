package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An extension of {@link HttpRouterHandler} that automatically scans packages
 * for annotated controllers and components, then registers them as routes.
 * <p>
 * Usage:
 * <pre>{@code
 * HTTPServer server = HTTPServer.of(8080);
 * server.requestHandler(
 *     new AnnotationRouterHandler()
 *         .scanPackages("com.example.controller")
 * );
 * server.start();
 * }</pre>
 * <p>
 * To bridge third-party annotations (e.g. Spring Boot), provide a custom
 * {@link AnnotationResolver} via {@link #annotationResolver(AnnotationResolver)}.
 *
 * @author wangyc
 */
public class AnnotationRouterHandler extends HttpRouterHandler {

    private AnnotationResolver resolver = new DefaultAnnotationResolver();
    private final BeanContainer beanContainer = new BeanContainer();
    private final Set<Object> processedConfigBeans = new HashSet<Object>();
    private HttpMessageConverter messageConverter;
    private Class<?>[] requestBodyAnnotations = new Class<?>[]{io.github.wycst.wastnet.http.annotation.RequestBody.class};
    private Class<?>[] responseBodyAnnotations = new Class<?>[]{io.github.wycst.wastnet.http.annotation.ResponseBody.class};

    public AnnotationRouterHandler() {
    }

    /**
     * Set the annotation classes recognized as {@code @RequestBody}.
     * <p>
     * Default is the framework's own {@code @RequestBody}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.RequestBody.class}
     * to bridge existing code.
     */
    public AnnotationRouterHandler requestBodyBy(Class<?>... anns) {
        this.requestBodyAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized as {@code @ResponseBody}.
     * <p>
     * Default is the framework's own {@code @ResponseBody}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.ResponseBody.class}
     * to bridge existing code.
     */
    public AnnotationRouterHandler responseBodyBy(Class<?>... anns) {
        this.responseBodyAnnotations = anns;
        return this;
    }

    /**
     * Set the message converter for processing {@code @RequestBody} and
     * {@code @ResponseBody} annotations.
     * <p>
     * When configured, {@code @RequestBody} parameters are automatically deserialized
     * from the HTTP request body, and controller methods annotated with
     * {@code @ResponseBody} are wired so their return values are automatically
     * serialized to the HTTP response body.
     * <p>
     * Default is {@code null} (annotations are ignored). Configure this to enable
     * automatic serialization/deserialization via e.g. JSON.
     */
    public AnnotationRouterHandler messageConverter(HttpMessageConverter converter) {
        this.messageConverter = converter;
        return this;
    }

    /**
     * Set a custom {@link AnnotationResolver} to bridge third-party annotation
     * systems (e.g. Spring Boot, Micronaut, etc.).
     * <p>
     * This is the central extension point of the framework. By implementing
     * {@link AnnotationResolver}, users can:
     * <ul>
     *   <li>Map third-party annotations (e.g. {@code @RestController},
     *       {@code @Service}, {@code @GetMapping}) to the framework's
     *       controller/component/endpoint model</li>
     *   <li>Provide a custom scanner filter via {@link AnnotationFilter#accept(Class)}
     *       to avoid loading irrelevant classes during package scanning</li>
     *   <li>Control how route paths, component names, and lifecycle annotations
     *       are resolved from external annotation libraries</li>
     * </ul>
     * <p>
     * Default is {@link DefaultAnnotationResolver}, which resolves the
     * framework's own annotations ({@code @Controller}, {@code @Component},
     * {@code @Endpoint}, {@code @Sse}, {@code @WebSocket}).
     * <p>
     * Example for Spring Boot bridging:
     * <pre>{@code
     * new AnnotationRouterHandler()
     *     .annotationResolver(new AnnotationResolver() {
     *         public boolean accept(Class<?> c) {
     *             return c.isAnnotationPresent(RestController.class)
     *                 || c.isAnnotationPresent(Component.class);
     *         }
     *         public boolean isController(Class<?> c) { ... }
     *         public boolean isComponent(Class<?> c)   { ... }
     *         // ... other methods
     *     })
     *     .scanPackages("com.example.controller");
     * }</pre>
     */
    public AnnotationRouterHandler annotationResolver(AnnotationResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    /**
     * Scan one or more packages for {@code @Controller}, {@code @Component}
     * and {@code @WebSocket} classes.
     * Components are registered first so they are available for constructor injection
     * into controllers.
     */
    public AnnotationRouterHandler scanPackages(String... packageNames) {
        List<Class<?>> allControllers = new ArrayList<Class<?>>();
        List<Class<?>> allWebSocketClasses = new ArrayList<Class<?>>();
        for (String pkg : packageNames) {
            for (Class<?> clazz : classifyClasses(pkg)) {
                if (resolver.isController(clazz)) {
                    allControllers.add(clazz);
                } else if (resolver.isComponent(clazz)) {
                    registerComponent(clazz);
                } else if (resolver.isWebSocketEndpoint(clazz)) {
                    allWebSocketClasses.add(clazz);
                } else if (clazz.isAnnotationPresent(Configuration.class)) {
                    registerComponent(clazz);
                }
            }
        }
        processBeanMethods();
        // Retry deferred @Inject field injections (dependencies from @Bean results)
        beanContainer.retryDeferredFields();
        for (Class<?> c : allControllers) registerController(c);
        for (Class<?> c : allWebSocketClasses) registerWebSocketEndpoint(c);
        return this;
    }

    /** Scan a package and return eligible classes. */
    private Set<Class<?>> classifyClasses(String packageName) {
        Set<Class<?>> classes = PackageScanner.scan(packageName, resolver);
        Set<Class<?>> result = new LinkedHashSet<Class<?>>();
        for (Class<?> clazz : classes) {
            if (isEligibleClass(clazz)) result.add(clazz);
        }
        return result;
    }

    /**
     * Scan {@code @Bean} methods on all registered component instances and
     * register their return values back into the container.
     */
    private void processBeanMethods() {
        // Snapshot to avoid scanning beans registered by @Bean methods during iteration
        List<Object> snapshot = new ArrayList<Object>(beanContainer.getBeans());
        List<Method> deferred = new ArrayList<Method>();
        for (Object bean : snapshot) {
            if (!bean.getClass().isAnnotationPresent(Configuration.class)) continue;
            if (!processedConfigBeans.add(bean)) continue;
            for (Method method : bean.getClass().getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                Bean beanAnn = method.getAnnotation(Bean.class);
                if (beanAnn == null) continue;
                if (!tryProcessBeanMethod(bean, method, beanAnn)) {
                    deferred.add(method);
                }
            }
        }
        // Retry deferred @Bean methods (up to 4 retries for deep dependency chains)
        if (!deferred.isEmpty()) {
            List<Method> pending = deferred;
            int retry = 4;
            while (--retry > -1 && !pending.isEmpty()) {
                List<Method> next = new ArrayList<Method>();
                for (Method method : pending) {
                    Object bean = beanContainer.getBean(method.getDeclaringClass());
                    if (bean != null && tryProcessBeanMethod(bean, method, method.getAnnotation(Bean.class))) {
                        continue;
                    }
                    next.add(method);
                }
                pending = next;
            }
            if (!pending.isEmpty()) {
                throw new RuntimeException("Cannot resolve dependencies for @Bean after "
                        + "4 retries: " + pending);
            }
        }
    }

    /** @return true if resolved and invoked, false if deferred due to missing deps */
    private boolean tryProcessBeanMethod(Object bean, Method method, Bean beanAnn) {
        try {
            Object[] args = resolveParameters(method.getParameters(),
                    "@Bean method " + method.getName() + " on " + bean.getClass().getName());
            if (args == null) return false;
            invokeBeanMethod(bean, method, beanAnn, args);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process @Bean method " + method.getName()
                    + " on " + bean.getClass().getName(), e);
        }
    }

    private void invokeBeanMethod(Object bean, Method method, Bean beanAnn, Object[] args) throws Exception {
        Object result = method.invoke(bean, args);
        String name = beanAnn.value().isEmpty() ? method.getName() : beanAnn.value();
        beanContainer.register(name, result);
    }

    private void registerWebSocketEndpoint(Class<?> clazz) {
        try {
            String path = resolver.resolveWebSocketPath(clazz);
            if (path.isEmpty()) return;
            WebSocketResource resource = (WebSocketResource) clazz.newInstance();
            ws(path, resource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register WebSocket endpoint: " + clazz.getName(), e);
        }
    }

    private void registerComponent(Class<?> clazz) {
        try {
            Object instance = clazz.newInstance();
            String name = resolver.resolveComponentName(clazz);
            beanContainer.register(name, instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register component: " + clazz.getName(), e);
        }
    }

    /**
     * Register a controller's bean in the container so it is also managed
     * and receives lifecycle callbacks.
     */
    private void registerControllerBean(String name, Object controller) {
        try {
            beanContainer.register(name, controller);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize controller: " + controller.getClass().getName(), e);
        }
    }

    private void registerController(Class<?> clazz) {
        String basePath = resolver.resolveControllerPath(clazz);
        List<MethodRouteInfo> routes = resolver.resolveEndpointRoutes(clazz);
        List<MethodRouteInfo> sseEndpoints = resolver.resolveSseEndpoints(clazz);
        if (routes.isEmpty() && sseEndpoints.isEmpty()) return;

        try {
            final Object controller = resolveControllerInstance(clazz);
            registerControllerBean(clazz.getSimpleName(), controller);

            // ── HTTP routes ──
            for (final MethodRouteInfo routeInfo : routes) {
                final String fullPath = combinePath(basePath, routeInfo.getPath());

                // Pre-compute parameter kinds at scan time
                Method endpointMethod = routeInfo.getMethod();
                Parameter[] params = endpointMethod.getParameters();
                final int[] argKinds = new int[params.length];
                final Class<?>[] argBodyTypes = new Class<?>[params.length];
                boolean hasBody = false;
                for (int i = 0; i < params.length; i++) {
                    Class<?> pType = params[i].getType();
                    if (pType == HttpRequest.class) {
                        argKinds[i] = KIND_REQUEST;
                    } else if (pType == HttpResponse.class) {
                        argKinds[i] = KIND_RESPONSE;
                    } else if (isRequestBodyParam(params[i])) {
                        argKinds[i] = KIND_BODY;
                        argBodyTypes[i] = pType;
                        hasBody = true;
                    }
                }
                final boolean isFastPath = argKinds.length == 2 && argKinds[0] == KIND_REQUEST && argKinds[1] == KIND_RESPONSE;
                final boolean hasResponseBody = endpointMethod.getReturnType() != void.class
                        && hasResponseBodyAnnotation(endpointMethod);
                if (hasResponseBody && messageConverter == null) {
                    throw new RuntimeException("@ResponseBody on " + endpointMethod.getName()
                            + " requires a messageConverter configured via .messageConverter()");
                }
                final ConverterConfig converterConfig = buildConverterConfig(routeInfo);

                // Build MethodHandle bound to the controller instance
                MethodHandle mh = MethodHandles.lookup().unreflect(endpointMethod);
                final MethodHandle bound = MethodHandles.insertArguments(mh, 0, controller);

                HttpRoute handler;
                if (isFastPath) {
                    // Fast path: (HttpRequest, HttpResponse) — avoid array allocation
                    handler = new HttpRoute() {
                        @Override
                        public void handle(String p, HttpRequest request, HttpResponse response) throws Throwable {
                            if (hasResponseBody) {
                                Object result = bound.invoke((HttpRequest) request, (HttpResponse) response);
                                messageConverter.write(result, converterConfig, response);
                            } else {
                                bound.invokeExact((HttpRequest) request, (HttpResponse) response);
                            }
                        }
                    };
                } else {
                    // General path: unusual signatures
                    final boolean hasBodyFinal = hasBody;
                    handler = new HttpRoute() {
                        @Override
                        public void handle(String p, HttpRequest request, HttpResponse response) throws Throwable {
                            Object[] args = new Object[argKinds.length];
                            if (!hasBodyFinal) {
                                for (int i = 0; i < argKinds.length; i++) {
                                    args[i] = argKinds[i] == KIND_REQUEST ? request : response;
                                }
                            } else {
                                for (int i = 0; i < argKinds.length; i++) {
                                    switch (argKinds[i]) {
                                        case KIND_REQUEST:  args[i] = request; break;
                                        case KIND_RESPONSE: args[i] = response; break;
                                        case KIND_BODY:     args[i] = messageConverter != null
                                                ? messageConverter.read(request, converterConfig, argBodyTypes[i]) : null; break;
                                    }
                                }
                            }
                            Object result = bound.invokeWithArguments(args);
                            if (hasResponseBody) {
                                messageConverter.write(result, converterConfig, response);
                            }
                        }
                    };
                }

                HttpMethod[] httpMethods = routeInfo.getHttpMethods();
                exactRoute(fullPath, handler, httpMethods);
            }

            // ── SSE endpoints ──
            for (final MethodRouteInfo routeInfo : sseEndpoints) {
                final String fullPath = combinePath(basePath, routeInfo.getPath());

                MethodHandle mh = MethodHandles.lookup().unreflect(routeInfo.getMethod());
                final MethodHandle bound = MethodHandles.insertArguments(mh, 0, controller);

                sse(fullPath, emitter -> bound.invokeExact(emitter));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to register controller: " + clazz.getName(), e);
        }
    }

    /**
     * Check if a class is eligible for registration: must be public and non-abstract.
     */
    private static boolean isEligibleClass(Class<?> clazz) {
        int mod = clazz.getModifiers();
        return Modifier.isPublic(mod) && !Modifier.isAbstract(mod);
    }

    private Object resolveControllerInstance(Class<?> clazz) throws Exception {
        Constructor<?>[] constructors = clazz.getConstructors();
        Constructor<?> ctor = constructors.length > 0 ? constructors[0] : null;
        if (ctor == null) return clazz.newInstance();
        Parameter[] params = ctor.getParameters();
        if (params.length == 0) return clazz.newInstance();
        Object[] args = resolveParameters(params, clazz.getName());
        return ctor.newInstance(args);
    }

    /**
     * Resolve method/constructor parameters supporting {@code @Value}, {@code @Inject(name)},
     * or by-type lookup from the container.
     */
    /** @return args if all resolved, or null if any @Inject dependency is not yet available */
    private Object[] resolveParameters(Parameter[] params, String context) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            Value valueAnn = param.getAnnotation(Value.class);
            if (valueAnn != null) {
                args[i] = beanContainer.resolveValue(valueAnn.value(), param.getType());
                continue;
            }
            Inject injectAnn = param.getAnnotation(Inject.class);
            if (injectAnn != null && !injectAnn.value().isEmpty()) {
                args[i] = beanContainer.getBean(injectAnn.value());
            } else {
                args[i] = beanContainer.getBean(param.getType());
            }
            if (args[i] == null) {
                if (injectAnn != null) return null; // deferrable
                throw new RuntimeException("Cannot resolve dependency '"
                        + param.getType().getSimpleName() + "' for " + context);
            }
        }
        return args;
    }

    protected ConverterConfig buildConverterConfig(MethodRouteInfo routeInfo) {
        return new ConverterConfig();
    }

    private static final int KIND_REQUEST = 0;
    private static final int KIND_RESPONSE = 1;
    private static final int KIND_BODY = 2;

    private boolean hasResponseBodyAnnotation(Method method) {
        for (Annotation ann : method.getAnnotations()) {
            for (Class<?> cfg : responseBodyAnnotations) {
                if (cfg.equals(ann.annotationType())) return true;
            }
        }
        return false;
    }

    private boolean isRequestBodyParam(Parameter param) {
        for (Annotation ann : param.getAnnotations()) {
            for (Class<?> cfg : requestBodyAnnotations) {
                if (cfg.equals(ann.annotationType())) return true;
            }
        }
        return false;
    }

    private static String combinePath(String base, String path) {
        if (base == null || base.isEmpty() || "/".equals(base)) {
            return path.startsWith("/") ? path : "/" + path;
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Set the annotation classes used for dependency injection.
     * <p>
     * Default is {@link io.github.wycst.wastnet.http.annotation.Inject @Inject}.
     * Pass e.g. Spring's {@code Autowired.class} to bridge third-party annotations.
     */
    public AnnotationRouterHandler injectBy(Class<?>... anns) {
        beanContainer.setInjectAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes used for initialization callbacks.
     * <p>
     * Default is {@link io.github.wycst.wastnet.http.annotation.PostConstruct @PostConstruct}.
     */
    public AnnotationRouterHandler postConstructBy(Class<?>... anns) {
        beanContainer.setPostConstructAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes used for destruction callbacks.
     * <p>
     * Default is {@link io.github.wycst.wastnet.http.annotation.PreDestroy @PreDestroy}.
     */
    public AnnotationRouterHandler preDestroyBy(Class<?>... anns) {
        beanContainer.setPreDestroyAnnotations(anns);
        return this;
    }

    /**
     * Set a configuration property available for {@code @Value} injection.
     */
    public AnnotationRouterHandler property(String key, String value) {
        beanContainer.setProperty(key, value);
        return this;
    }

    /**
     * Set multiple configuration properties available for {@code @Value} injection.
     */
    public AnnotationRouterHandler properties(Map<String, String> props) {
        beanContainer.setProperties(props);
        return this;
    }

    /**
     * Load configuration from one or more classpath {@code .properties} files.
     * <p>
     * Example:
     * <pre>{@code
     * new AnnotationRouterHandler().loadProperties("application.properties")
     * }</pre>
     */
    public AnnotationRouterHandler loadProperties(String... classpathResources) {
        beanContainer.loadProperties(classpathResources);
        return this;
    }

    /**
     * Load configuration via a custom {@link ConfigLoader}.
     * <p>
     * Example:
     * <pre>{@code
     * new AnnotationRouterHandler().loadConfig(config -> {
     *     config.put("key", value);
     * })
     * }</pre>
     */
    public AnnotationRouterHandler loadConfig(ConfigLoader loader) {
        beanContainer.loadConfig(loader);
        return this;
    }

    /**
     * Clear all registered routes and release component instances.
     */
    @Override
    public void clear() {
        super.clear();
        beanContainer.clear();
        processedConfigBeans.clear();
    }
}
