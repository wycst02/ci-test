package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.HttpMessageDecoder;
import io.github.wycst.wastnet.http.HttpRequestDecoder;
import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * <p> http/1.0/1.1 decode </p>
 *
 * @Date 2024/1/19 15:57
 * @Created by wangyc
 */
public class HttpRequestReader extends HttpMessageReader<HttpMessage> {

    final static WebSocketDecoder WEBSOCKET_DECODER = new WebSocketDecoder();
    HttpMessageDecoder messageDecoder = new HttpRequestDecoder();

    @Override
    public void decode(ChannelContext ctx, byte[] buf, int offset, int len) throws IOException {
        messageDecoder.decode(buf, offset, len, ctx);
    }

    @Override
    public void upgrade(UpgradeHolder upgradeHolder) {
        // upgrade() is only called for WebSocket upgrades (h2c uses switchTo()), so isWebSocket() check is unnecessary
        messageDecoder = WEBSOCKET_DECODER;
    }
}