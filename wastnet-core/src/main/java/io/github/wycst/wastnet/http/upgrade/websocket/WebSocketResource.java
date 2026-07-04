package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.upgrade.UpgradeResource;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket resource handler.
 * <p>
 * Manages WebSocket connections for a given endpoint. Provides lifecycle callbacks
 * ({@link #onOpen}, {@link #onMessage}, {@link #onBinary}, {@link #onClose})
 * and supports idle timeout, broadcast, and payload size configuration.
 * <p>
 * Usage:
 * <pre>{@code
 * router.ws("/ws/chat", new WebSocketResource(300) {
 *     public void onOpen(WebSocketConnection conn) {
 *         System.out.println("open: " + conn.id());
 *     }
 *     public void onMessage(WebSocketConnection conn, String message) {
 *         System.out.println("message: " + message);
 *     }
 * });
 * }</pre>
 *
 * @author wangyc
 */
public class WebSocketResource extends UpgradeResource {

    final Map<String, WebSocketConnection> connectionMap = new ConcurrentHashMap<String, WebSocketConnection>();
    final boolean broadcast;
    // timeout in seconds (non-positive means no timeout)
    final int timeout;
    // timeout strategy: DISCONNECT or PING
    final TimeoutStrategy timeoutStrategy;

    // Max payload size per endpoint (defaults to global config, capped at Integer.MAX_VALUE)
    private int maxPayloadSize = HttpConf.MAX_WS_FRAME_SIZE;
    // Continuation frame handling strategy
    private ContinuationStrategy continuationStrategy = ContinuationStrategy.MERGE;

    public WebSocketResource() {
        this(true);
    }

    public WebSocketResource(boolean broadcast) {
        this(broadcast, 0);
    }

    /**
     * @param timeout idle timeout in seconds (non-positive means no timeout)
     */
    public WebSocketResource(int timeout) {
        this(true, timeout, TimeoutStrategy.DISCONNECT);
    }

    /**
     * @param timeout         idle timeout in seconds (non-positive means no timeout)
     * @param timeoutStrategy timeout handling strategy
     */
    public WebSocketResource(int timeout, TimeoutStrategy timeoutStrategy) {
        this(true, timeout, timeoutStrategy);
    }

    /**
     * @param broadcast whether to enable broadcast
     * @param timeout   idle timeout in seconds (non-positive means no timeout)
     */
    public WebSocketResource(boolean broadcast, int timeout) {
        this(broadcast, timeout, TimeoutStrategy.DISCONNECT);
    }

    /**
     * @param broadcast       whether to enable broadcast
     * @param timeout         idle timeout in seconds (non-positive means no timeout)
     * @param timeoutStrategy timeout handling strategy
     */
    public WebSocketResource(boolean broadcast, int timeout, TimeoutStrategy timeoutStrategy) {
        this.broadcast = broadcast;
        this.timeout = timeout;
        this.timeoutStrategy = timeoutStrategy;
    }

    public boolean enableBroadcast() {
        return broadcast;
    }

    public TimeoutStrategy getTimeoutStrategy() {
        return timeoutStrategy;
    }

    public int getTimeout() {
        return timeout;
    }

    /**
     * Pre-handshake validation. Return false to reject the WebSocket upgrade.
     *
     * @param request  the HTTP upgrade request
     * @param response the HTTP response (can be used to set error status)
     * @return true to allow upgrade, false to reject
     */
    public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
        return true;
    }

    /**
     * Internal callback: registers the connection and delegates to {@link #onOpen}.
     */
    public final void handleOnOpen(WebSocketConnection connection) {
        if (enableBroadcast()) {
            connectionMap.put(connection.id(), connection);
        }
        onOpen(connection);
    }

    /**
     * Called when a WebSocket connection is established.
     *
     * @param connection the established connection
     */
    public void onOpen(WebSocketConnection connection) {
    }

    /**
     * Called when a text message is received.
     *
     * @param connection the connection that sent the message
     * @param message    the received text
     */
    public void onMessage(WebSocketConnection connection, String message) throws IOException {
    }

    /**
     * Called when a binary message is received.
     *
     * @param connection the connection that sent the message
     * @param data       the received binary data
     */
    public void onBinary(WebSocketConnection connection, byte[] data) throws IOException {
    }

    /**
     * Called for every WebSocket frame received, regardless of frame type
     * (TEXT, BINARY, CONTINUATION, CLOSE, PING, PONG).
     * <p>
     * Note: With {@code MERGE} strategy, CONTINUATION frames are accumulated
     * in the decoder and never reach here — only the merged TEXT/BINARY frame
     * is delivered via {@link #onMessage} / {@link #onBinary}.
     *
     * @param connection the WebSocket connection
     * @param frame      the received frame
     * @throws IOException if an I/O error occurs
     */
    public void onFrame(WebSocketConnection connection, WebSocketFrame frame) throws IOException {
    }

    /**
     * Handle a CONTINUATION frame.
     * <p>
     * Only triggered when the {@link ContinuationStrategy} is not {@code MERGE}
     * (i.e. {@code BATCH} or {@code STREAM}). With {@code MERGE} strategy,
     * continuation frames are accumulated in the decoder and the complete message
     * is delivered via {@link #onMessage} or {@link #onBinary} instead.
     * <p>
     * Use {@link WebSocketFrame#isFin()} to determine whether this is the
     * last continuation frame for the current message.
     *
     * @param connection the WebSocket connection
     * @param frame      the continuation frame
     * @throws IOException if an I/O error occurs
     */
    public void onContinuation(WebSocketConnection connection, WebSocketFrame frame) throws IOException {
    }

    /**
     * Called when a WebSocket connection is closed normally.
     *
     * @param connection the connection being closed
     * @param code       WebSocket close status code (e.g. 1000 for normal closure)
     * @param reason     human-readable close reason
     */
    public void onClose(WebSocketConnection connection, int code, String reason) {
    }

    public final void handleOnClose(WebSocketConnection connection, int code, String reason) {
        if (enableBroadcast()) {
            connectionMap.remove(connection.id());
        }
        onClose(connection, code, reason);
    }

    public void onError(WebSocketConnection connection, Throwable error) {
    }

    @Override
    public final boolean isWebSocket() {
        return true;
    }

    /**
     * Disconnect all connections.
     */
    public final void disconnect() {
        disconnect(null);
    }

    /**
     * Disconnect all connections for the specified group.
     *
     * @param groupId the group id
     */
    public final void disconnect(Serializable groupId) {
        Iterator<Map.Entry<String, WebSocketConnection>> iterator = connectionMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WebSocketConnection> entry = iterator.next();
            WebSocketConnection webSocketConnection = entry.getValue();
            if (groupId == null || groupId.equals(webSocketConnection.getGroupId())) {
                webSocketConnection.disconnect();
                iterator.remove();
            }
        }
    }

    /**
     * Disconnect all connections for the specified account.
     *
     * @param account the account id
     */
    public final void disconnectByAccount(Serializable account) {
        if (account == null) return;
        Iterator<Map.Entry<String, WebSocketConnection>> iterator = connectionMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WebSocketConnection> entry = iterator.next();
            WebSocketConnection webSocketConnection = entry.getValue();
            if (account.equals(webSocketConnection.getAccount())) {
                webSocketConnection.disconnect();
                iterator.remove();
            }
        }
    }

    /**
     * Broadcast message to all connections.
     *
     * @param frame the frame to broadcast
     */
    public final void broadcastMessage(WebSocketFrame frame) {
        broadcastMessage(frame, null);
    }

    /**
     * Broadcast message to specified group.
     *
     * @param frame   the frame to broadcast
     * @param groupId the group id
     */
    public final void broadcastMessage(WebSocketFrame frame, String groupId) {
        Collection<WebSocketConnection> webSocketConnections = connectionMap.values();
        for (WebSocketConnection webSocketConnection : webSocketConnections) {
            if (groupId == null || groupId.equals(webSocketConnection.getGroupId())) {
                webSocketConnection.push(frame);
            }
        }
    }

    /**
     * Push message to specified account.
     *
     * @param frame   the frame to push
     * @param account the account id
     */
    public final void pushMessage(WebSocketFrame frame, String account) {
        WebSocketConnection webSocketConnection = getConnectionByAccount(account);
        if (webSocketConnection != null) {
            webSocketConnection.push(frame);
        }
    }

    /**
     * Get connection by account.
     *
     * @param account the account id
     * @return the connection
     */
    public WebSocketConnection getConnectionByAccount(String account) {
        if (account == null) return null;
        Collection<WebSocketConnection> webSocketConnections = connectionMap.values();
        for (WebSocketConnection webSocketConnection : webSocketConnections) {
            if (account.equals(webSocketConnection.getAccount())) {
                return webSocketConnection;
            }
        }
        return null;
    }

    /**
     * Handle abnormal close.
     *
     * @param ctx the channel context
     */
    @Override
    public final void handleOnClose(ChannelContext ctx) {
        WebSocketConnection webSocketConnection = connectionMap.get(ctx.contextId());
        if (webSocketConnection != null) {
            onErrorClose(webSocketConnection);
        }
    }

    public void onErrorClose(WebSocketConnection connection) {
    }

    /**
     * Get supported subprotocol, returns the first one by default.
     *
     * @param supportedSubprotocols supported subprotocols
     * @return the selected subprotocol
     */
    public String getSubprotocols(String supportedSubprotocols) {
        if (supportedSubprotocols == null) return null;
        int index = supportedSubprotocols.indexOf(",");
        if (index > 0) {
            return supportedSubprotocols.substring(0, index);
        }
        return supportedSubprotocols;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public WebSocketResource maxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    public ContinuationStrategy getContinuationStrategy() {
        return continuationStrategy;
    }

    public WebSocketResource continuationStrategy(ContinuationStrategy strategy) {
        this.continuationStrategy = strategy;
        return this;
    }

    /**
     * Continuation frame handling strategy.
     */
    public enum ContinuationStrategy {
        /** Dispatch every frame individually via onFrame, no merging. */
        STREAM,
        /** Buffer continuation frames, flush intermediate merged chunks when approaching maxPayloadSize. */
        BATCH,
        /** Merge all continuation frames into one message; 1009 error if exceeds maxPayloadSize. */
        MERGE
    }

    /**
     * WebSocket timeout strategy.
     */
    public enum TimeoutStrategy {
        /**
         * Disconnect on timeout.
         */
        DISCONNECT,
        /**
         * Send ping on timeout to keep alive.
         */
        PING
    }

}
