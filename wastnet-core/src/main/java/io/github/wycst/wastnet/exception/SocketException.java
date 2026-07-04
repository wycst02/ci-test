package io.github.wycst.wastnet.exception;

/**
 * @Date 2024/1/14 22:40
 * @Created by wangyc
 */
public class SocketException extends RuntimeException {

    public SocketException(String message) {
        super(message);
    }

    public SocketException(String message, Throwable cause) {
        super(message, cause);
    }
}
