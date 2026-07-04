package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.h2.Http2MessageReader;
import io.github.wycst.wastnet.http.h2.Http2ServerReader;
import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.socket.channel.ChannelDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p> single instance in one thread to aggregator http request. </p>
 *
 * <p> one channel one reader</p>
 *
 * @Date 2024/1/19 15:57
 * @Created by wangyc
 */
public final class HttpChannelProtocolReader extends ChannelDecoder<HttpMessage> {

    private HttpMessageReader<HttpMessage> reader;

    public HttpChannelProtocolReader() {
        reader = new HttpRequestReader();
    }

    @Override
    public void init(ChannelContext ctx) throws Exception {
        if (ctx.isChannelClosed()) return;
        String shakedApplicationProtocol = ctx.getHandShakedApplicationProtocol();
        if ("h2".equals(shakedApplicationProtocol)) {
            reader = new Http2ServerReader();
        } else if (!ctx.isSSL() && ctx.hasApplicationProtocol("h2c")) {
            byte[] buf = new byte[24];
            try {
                readInternal(ctx, buf, 0, 24, 10000L);
                if (Http2MessageReader.validatePreface(buf)) {
                    reader = new Http2ServerReader().replyServerSettings(ctx);
                    // skip init
                    return;
                }
                reader = new HttpRequestReader();
                reader.decode(ctx, buf, 0, 24);
            } catch (IOException e) {
                ctx.close();
                return;
            }
        } else {
            reader = new HttpRequestReader();
        }
        reader.init(ctx);
    }

    @Override
    public void decode(ChannelContext ctx, ByteBuffer buf) throws IOException {
        reader.decode(ctx, buf);
    }

    public void upgrade(UpgradeHolder upgradeHolder) {
        reader.upgrade(upgradeHolder);
    }

    /**
     * Switch the underlying protocol reader (e.g. from HTTP/1.x to HTTP/2).
     *
     * @param reader the new protocol reader
     */
    public void switchTo(HttpMessageReader<HttpMessage> reader) {
        this.reader = reader;
    }
}
