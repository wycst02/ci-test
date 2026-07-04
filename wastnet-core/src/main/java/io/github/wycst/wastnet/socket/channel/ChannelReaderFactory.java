package io.github.wycst.wastnet.socket.channel;

/**
 * @Date 2024/1/21 10:55
 * @Created by wangyc
 */
public interface ChannelReaderFactory {

    /**
     * create reader
     *
     */
    ChannelReader<?> getChannelReader();
}
