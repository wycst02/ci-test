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
package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.ChannelSSLContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP proxy route for handling proxy requests.
 * Handles request forwarding and response relay.
 *
 * @author wangyc
 */
public class HttpProxyRoute extends HttpRoute implements ClearableHandler {

    static final Log log = LogFactory.getLog(HttpProxyRoute.class);

    private final HttpProxyConfig config;
    private final String routeId;
    private final String host;
    private final int port;
    final boolean ssl;
    final boolean defaultPort;
    private final String hostPort;
    private final String targetPath;
    private final HttpProxyWorker worker;
    private final String loopMarker;

    public HttpProxyRoute(HttpProxyConfig config) {
        this.config = config;
        this.routeId = UUID.randomUUID().toString();
        URL targetUrl;
        try {
            targetUrl = new URL(config.target);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Invalid proxy target: " + config.target, e);
        }
        this.host = targetUrl.getHost();
        this.ssl = "https".equalsIgnoreCase(targetUrl.getProtocol());
        this.defaultPort = targetUrl.getPort() == -1;
        this.port = !defaultPort ? targetUrl.getPort()
                : ssl ? 443 : 80;
        this.hostPort = this.host + ":" + this.port;
        // Extract target path (e.g. "http://host/a/b/c" → "/a/b/c"), strip trailing "/"
        String urlPath = targetUrl.getPath();
        if (urlPath.length() > 1 && urlPath.endsWith("/")) {
            urlPath = urlPath.substring(0, urlPath.length() - 1);
        }
        this.targetPath = urlPath.length() > 1 ? urlPath : null;
        try {
            this.worker = HttpProxyWorkerManager.INSTANCE.get(hostPort);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create proxy worker", e);
        }
        // Initialize loop marker only when loop detection is enabled
        this.loopMarker = config.isLoopDetection()
                ? HttpHeaderUtils.normalizeHeaderKey("X-Forwarded-Proxy-" + routeId)
                : null;
    }

    private ChannelContext createTargetContext(long connectionId, String host, int port, HttpRequest request) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.connect(new InetSocketAddress(host, port));

        // Wait for connection completion in non-blocking mode
        long deadline = System.currentTimeMillis() + config.connectionTimeout; // default 5s timeout
        while (!socketChannel.finishConnect()) {
            if (System.currentTimeMillis() > deadline) {
                socketChannel.close();
                throw new IOException("Connection timeout to " + host + ":" + port);
            }
            Thread.yield();
        }

        if (ssl) {
            String[] alpn = (request.getHttpVersion() == HttpVersion.HTTP_2 && config.http2) ? new String[]{"h2", "http/1.1"} : null;
            return ChannelSSLContext.createClientContext(connectionId, socketChannel, alpn);
        }
        // Create ChannelContext with same id as clientCtx, buffer size 8KB
        return new ChannelContext(connectionId, socketChannel, 8192);
    }

    /**
     * Create a new proxy connection.
     * <p>
     * If an old connection is provided and was replaced (busy → new), its adapter is
     * marked as disposed so it will be cleaned up automatically after its pending response completes.
     *
     * @param oldConnection the connection being replaced, or null
     * @return the new connection, or null if creation failed (error already set on response)
     */
    private HttpProxyConnection createConnection(Long connectionId, ChannelContext clientCtx, HttpRequest request, HttpResponse response, HttpProxyConnection oldConnection) {
        ChannelContext targetCtx;
        try {
            targetCtx = createTargetContext(connectionId, host, port, request);
        } catch (SocketTimeoutException e) {
            response.setStatusAndText(HttpStatus.GATEWAY_TIMEOUT);
            log.error("[HttpProxy] Gateway timeout: {}", e.getMessage());
            return null;
        } catch (IOException e) {
            response.setStatusAndText(HttpStatus.BAD_GATEWAY);
            log.error("[HttpProxy] Bad gateway: {}", e.getMessage());
            return null;
        }
        HttpProxyConnection conn;
        try {
            conn = new HttpProxyConnection(connectionId, routeId, clientCtx, targetCtx, worker);
        } catch (IOException e) {
            response.setStatusAndText(HttpStatus.INTERNAL_SERVER_ERROR);
            log.error("[HttpProxy] Failed to create proxy connection: {}", e.getMessage());
            return null;
        }
        // store first connection in map; concurrent temp connections are disposed after use
        if (oldConnection == null) {
            worker.connections.put(connectionId, conn);
        } else {
            conn.disposed = true;
        }
        return conn;
    }

    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        HttpInternalRequest internalRequest = (HttpInternalRequest) request;
        ChannelContext clientCtx = internalRequest.ctx();
        // Loop detection: use unique header name per proxy
        if (loopMarker != null) {
            if (request.containsHeader(loopMarker)) {
                // Loop detected: request already passed through this proxy
                response.setStatusAndText(HttpStatus.LOOP_DETECTED);
                log.warn("[HttpProxy] Loop detected, marker: {}", loopMarker);
                return;
            }
            internalRequest.setHeader(loopMarker, "1");
        }
        // Handle rewrite or target path
        if (config.rewriteRule != null) {
            try {
                String newUri = config.rewriteRule.apply(path);
                // Ensure newUri starts with "/"
                if (newUri != null && !newUri.startsWith("/") && !newUri.toLowerCase().startsWith("http")) {
                    newUri = "/" + newUri;
                }
                internalRequest.setRewriteUri(newUri);
                System.out.println("target uri: " + internalRequest.getUri());
            } catch (Exception e) {
                response.setStatusAndText(HttpStatus.INTERNAL_SERVER_ERROR);
                log.error("[HttpProxy] Rewrite error: {}", e.getMessage());
                return;
            }
        } else if (targetPath != null) {
            // Target URL contains a non-trivial path (e.g. /a/b/c), prepend it
            String newPath = targetPath + path;
            internalRequest.setRewriteUri(newPath);
            System.out.println("target uri: " + internalRequest.getUri());
        }
        if (config.changeOrigin) {
            internalRequest.setHeader(HttpHeaderNormalized.getHost(), defaultPort ? host : hostPort);
        }
        // Apply header modifications
        if (!config.headers.isEmpty()) {
            for (Map.Entry<String, HttpProxyConfig.HeaderValueResolver> entry : config.headers.entrySet()) {
                internalRequest.setHeader(entry.getKey(), entry.getValue().resolve(request));
            }
        }
        if (!config.removedHeaders.isEmpty()) {
            for (String name : config.removedHeaders) {
                internalRequest.removeHeader(name);
            }
        }
        Long connectionId = request.getConnectionId();
        HttpProxyConnection connection;
        synchronized (worker.connections) {
            connection = worker.connections.get(connectionId);
            // CAS acquire adapter: if busy, create a new connection (H2→H1 serves one stream at a time)
            if (connection == null || !connection.adapter.tryAcquire()) {
                connection = createConnection(connectionId, clientCtx, request, response, connection);
                if (connection == null) return;
            }
        }
        // Hand over response control to proxy; response will be written via raw channel context
        ((HttpCompleteResponse) response).handover();
        connection.forwardToTarget(request, config);
    }

    @Override
    public void clear() {
        worker.clearConnections(routeId);
    }

    @Override
    public Map<String, Object> getRouteInfo() {
        Map<String, Object> info = new HashMap<String, Object>();
        info.put("type", "proxy");
        info.put("target", config.target);
        info.put("ssl", ssl);
        info.put("port", port);
        info.put("loopDetection", config.isLoopDetection());
        info.put("activeConnections", worker.connections.size());
        return info;
    }
}