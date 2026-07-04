package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpUpgradeMessage;
import io.github.wycst.wastnet.http.h2.Http2ServerReader;
import io.github.wycst.wastnet.http.reader.HttpChannelProtocolReader;
import io.github.wycst.wastnet.http.upgrade.websocket.*;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.util.Map;

/**
 * Default implementation of {@link UpgradeHandler}.
 * <p>
 * Manages WebSocket and h2c resources in a map, handles WebSocket frame dispatching,
 * and performs protocol upgrade handshake.
 *
 * @author wangyc
 */
public class DefaultUpgradeHandler implements UpgradeHandler {

    static final Log log = LogFactory.getLog(DefaultUpgradeHandler.class);

    final Map<String, UpgradeResource> resourceHashMap = new java.util.HashMap<String, UpgradeResource>();
    static final byte[] WEBSOCKET_PONG_FRAME = new byte[]{(byte) 0x8A, 0x00};
    static final byte[] H2C_101_RESPONSE = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n".getBytes(Utils.ISO_8859_1);

    /**
     * Get upgrade resource by path.
     * <p>
     * Tries exact match first, falls back to prefix match.
     * Prefix match supports dynamic paths like {@code /ws/chat/user/1001}
     * matching a resource registered at {@code /ws/chat/user/}.
     */
    UpgradeResource getResource(String path) {
        if (resourceHashMap.isEmpty()) return null;
        UpgradeResource res = resourceHashMap.get(path);
        if (res != null) return res;
        for (Map.Entry<String, UpgradeResource> entry : resourceHashMap.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public final void handle(ChannelContext ctx, HttpUpgradeMessage upgradeMessage) throws Throwable {
        // Only WebSocket frames reach here (h2c upgrade switches decoder in upgrade());
        // HttpUpgradeMessage.isWebSocket() is always true at this point.
        handleWebSocket(ctx, upgradeMessage);
    }

    private void handleWebSocket(ChannelContext ctx, HttpUpgradeMessage upgradeMessage) throws IOException {
        WebSocketFrame frame = (WebSocketFrame) upgradeMessage;
        WebSocketFrame.FrameType type = frame.getType();

        UpgradeWebSocketHolder upgradeHolder = (UpgradeWebSocketHolder) ctx.binding();
        if (upgradeHolder == null) return; // connection closed/timed out
        WebSocketResource resource = upgradeHolder.resource;
        WebSocketConnection connection = upgradeHolder.connection;
        try {
            connection.updateActiveTime();
            switch (type) {
                case CONTINUATION:
                    resource.onContinuation(connection, frame);
                    break;
                case TEXT:
                    String textMessage = new String(frame.getData(), Utils.UTF_8);
                    resource.onMessage(connection, textMessage);
                    break;
                case BINARY:
                    resource.onBinary(connection, frame.getData());
                    break;
                case CLOSE:
                    handleCloseFrame(ctx, frame, resource, connection);
                    break;
                case PING:
                    sendPongFrame(ctx, frame.getData());
                    break;
                case PONG:
                    break;
            }
            resource.onFrame(connection, frame);
        } catch (Exception e) {
            try {
                resource.onError(connection, e);
            } catch (Exception errorEx) {
                log.error("Error in WebSocket error handler: {}", errorEx.getMessage());
            }
            try {
                connection.close(1011, "Internal server error");
            } catch (Exception closeEx) {
                log.error("Failed to close WebSocket connection: {}", closeEx.getMessage());
            }
        }
    }

    /**
     * Handle WebSocket close frame.
     * <p>
     * Parses close code and reason, notifies resource, sends close frame, and closes connection.
     */
    private void handleCloseFrame(ChannelContext ctx, WebSocketFrame frame, WebSocketResource resource, WebSocketConnection connection) {
        try {
            byte[] data = frame.getData();
            int closeCode = 1000; // default normal closure
            String reason = "Normal closure";

            // parse close code and reason
            if (data.length >= 2) {
                closeCode = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                if (data.length > 2) {
                    reason = new String(data, 2, data.length - 2, Utils.UTF_8);
                }
            }

            // notify resource of close
            resource.handleOnClose(connection, closeCode, reason);

            // send close frame
            WebSocketUtils.sendCloseFrame(ctx, closeCode, reason);

            // close connection
            ctx.close();
        } catch (Exception e) {
            log.error("Error handling close frame: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * Send pong frame in response to ping.
     */
    private void sendPongFrame(ChannelContext ctx, byte[] pingData) {
        try {
            if (pingData.length == 0) {
                ctx.writeFlush(WEBSOCKET_PONG_FRAME);
            } else {
                ctx.writeFlush(WebSocketUtils.encodeServerFrame(WebSocketFrame.FrameType.PONG, pingData, true));
            }
        } catch (Exception e) {
            log.error("Failed to send pong frame: {}", e.getMessage());
        }
    }

    /**
     * Check if the HTTP request contains a valid h2c (HTTP/2 Cleartext) upgrade request.
     * <p>
     * Validates per RFC 7540 Section 3.2:
     * <pre>
     * Upgrade: h2c
     * Connection: contains HTTP2-Settings
     * </pre>
     *
     * @param request the HTTP request
     * @return true if this is a valid h2c upgrade request
     */
    public static boolean isH2cUpgradeRequest(HttpRequest request) {
        String upgrade = request.getHeader(HttpHeaderNormalized.getUpgrade(), true);
        if (!HttpHeaderValues.H2C.equalsIgnoreCase(upgrade)) return false;
        String connection = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        return connection != null
                && connection.contains("HTTP2-Settings")
                && connection.contains("Upgrade");
    }


    @Override
    public void h2c(String path) {
        resourceHashMap.put(path, UpgradeResource.H2C);
    }

    @Override
    public WebSocketResource ws(String path, WebSocketResource webSocketResource) {
        resourceHashMap.put(path, webSocketResource.path(path));
        return webSocketResource;
    }

    @Override
    public boolean upgrade(HttpRequest request, ChannelContext ctx) throws Exception {
        UpgradeResource upgradeResource = getResource(request.getRequestUri());
        if (upgradeResource == null) return false;
        if (upgradeResource.isWebSocket()) {
            WebSocketResource webSocketResource = (WebSocketResource) upgradeResource;
            WebSocketResponse response = WebSocketResponse.create(request, ctx);
            if (webSocketResource.beforeHandshake(request, response)) {
                String subprotocols = webSocketResource.getSubprotocols(response.getSupportedSubprotocols());
                WebSocketConnection connection = WebSocketUtils.handshake(request, response, ctx, subprotocols);
                if (connection != null) {
                    webSocketResource.handleOnOpen(connection);
                    if (ctx.binding() != null) {
                        log.warn("WebSocket upgrade rejected: beforeHandshake set binding on ctx");
                        return false;
                    }
                    UpgradeWebSocketHolder upgradeHolder = new UpgradeWebSocketHolder(webSocketResource, connection);
                    ctx.binding(upgradeHolder);
                    // start timeout detection
                    connection.timeoutDetection(
                            webSocketResource.getTimeout(),
                            webSocketResource.getTimeoutStrategy()
                    );
                    // perform protocol switch
                    ((HttpChannelProtocolReader) ctx.reader()).upgrade(upgradeHolder);
                    return true;
                }
            }
        } else {
            // h2c upgrade
            if (!isH2cUpgradeRequest(request)) return false;
            Http2ServerReader h2Reader = new Http2ServerReader();
            ((HttpChannelProtocolReader) ctx.reader()).switchTo(h2Reader);
            ctx.writeFlush(H2C_101_RESPONSE);
            h2Reader.init(ctx);
            return true;
        }
        return false;
    }

    @Override
    public void onClosed(ChannelContext ctx) throws IOException {
        UpgradeHolder upgradeHolder = (UpgradeHolder) ctx.binding();
        if (upgradeHolder != null) {
            upgradeHolder.upgradeResource().handleOnClose(ctx);
        }
    }
}
