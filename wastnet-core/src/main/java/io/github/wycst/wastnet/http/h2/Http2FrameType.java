package io.github.wycst.wastnet.http.h2;

/**
 * @Date 2024/2/26 15:16
 * @Created by wangyc
 */
public enum Http2FrameType {

    DATA(Http2Frame.FRAME_TYPE_DATA),
    HEADERS(Http2Frame.FRAME_TYPE_HEADERS),
    PRIORITY(Http2Frame.FRAME_TYPE_PRIORITY),
    RST_STREAM(Http2Frame.FRAME_TYPE_RST_STREAM),
    SETTINGS(Http2Frame.FRAME_TYPE_SETTINGS),
    PUSH_PROMISE(Http2Frame.FRAME_TYPE_PUSH_PROMISE),
    PING(Http2Frame.FRAME_TYPE_PING),
    GOAWAY(Http2Frame.FRAME_TYPE_GOAWAY),
    WINDOW_UPDATE(Http2Frame.FRAME_TYPE_WINDOW_UPDATE),
    CONTINUATION(Http2Frame.FRAME_TYPE_CONTINUATION);

    public final byte code;

    Http2FrameType(int code) {
        this.code = (byte) code;
    }

    public static Http2FrameType valueOf(int code) {
        Http2FrameType[] values = Http2FrameType.values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("invalid frame type code " + code);
        }
        return values[code];
    }
}
