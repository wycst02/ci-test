package io.github.wycst.wastnet.http;

/**
 * Upgrade Message
 *
 * @Date 2024/2/8 11:19
 * @Created by wangyc
 */
public interface HttpUpgradeMessage extends HttpMessage {

    boolean isWebSocket();

}
