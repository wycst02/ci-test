package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * codec for reader and writer
 *
 * @Date 2024/2/24
 * @Created by wangyc
 */
public abstract class ChannelCodec<T> extends ChannelDecoder<T> implements ChannelReader<T>, ChannelWriter<T> {

    @Override
    public void init(ChannelContext ctx) throws Exception {
    }

    @Override
    public void wakeup() {
    }
}
