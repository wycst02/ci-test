package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponseDecoder;
import io.github.wycst.wastnet.http.h2.H2ProxyHelper;
import io.github.wycst.wastnet.http.h2.Http2Request;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP/2 to HTTP/1.1 proxy adapter.
 * <p>
 * Converts H2 requests to H1 format for the target server,
 * and converts H1 responses back to H2 frames for the client.
 * <p>
 * Implements {@link ChannelHandler} to receive decoded H1 responses
 * from {@link HttpResponseDecoder} via {@code targetCtx.invokeHandle()}.
 * <p>
 * Each adapter instance is per proxy connection (non-singleton).
 * Stream IDs are extracted dynamically from each {@link Http2Request}.
 *
 * @author wangyc
 */
public class H2H1ProxyAdapter extends ChannelHandler<Object> implements HttpProxyAdapter {

    static final Log log = LogFactory.getLog(H2H1ProxyAdapter.class);

    private final HttpProxyConnection connection;
    private final HttpResponseDecoder responseDecoder;
    private final AtomicBoolean busy = new AtomicBoolean(true);

    public H2H1ProxyAdapter(HttpProxyConnection connection) {
        this.connection = connection;
        this.responseDecoder = new HttpResponseDecoder();
        connection.targetCtx.setChannelHandler(this);
    }

    // ==================== Request: H2 → H1 ====================

    public void sendRequest(HttpRequest request, ChannelContext targetCtx) throws Throwable {
        Http2Request h2Request = ((Http2Request) request);
        targetCtx.attachment(h2Request);
        try {
            H2ProxyHelper.sendH1Request(h2Request.stream(), targetCtx);
        } catch (Throwable t) {
            busy.set(false);
            log.error("[H2H1Proxy] sendRequest error: {}", t.getMessage());
            if (connection.isTargetClosed()) {
                connection.close(); // release resources if target closed
            }
            H2ProxyHelper.sendGatewayError(h2Request.stream()); // send gateway error back to H2 client
        }
    }

    @Override
    public void receiveResponse(ChannelContext targetCtx) throws InterruptedException {
    }

    /** Atomically acquire adapter: CAS idle(false)→busy(true), return true on success */
    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    // ==================== Response: H1 bytes → decoder ====================

    public void onData(ByteBuffer buffer, ChannelContext writeCtx, boolean isTarget, HttpProxyConnection conn) throws IOException {
        if (!isTarget) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        // System.out.println("Thread ID: " + Thread.currentThread().getId() + " - [H2H1] onData: \n" + new String(bytes));
        responseDecoder.decode(bytes, 0, bytes.length, conn.targetCtx);
    }

    // ==================== ChannelHandler callback ====================

    @Override
    public void onHandle(ChannelContext ctx, Object message) throws IOException {
        // System.out.println("Thread ID: " + Thread.currentThread().getId() + " - [H2H1] onHandle: \n" + message);
        try {
            HttpDecodedResponse targetResponse = (HttpDecodedResponse) message;
            Http2Request h2Request = (Http2Request) ctx.attachment();
            H2ProxyHelper.writeResponse(h2Request.stream(), targetResponse);
        } finally {
            busy.set(false);
            if (connection.disposed) {
                connection.close();
            }
        }
    }
}
