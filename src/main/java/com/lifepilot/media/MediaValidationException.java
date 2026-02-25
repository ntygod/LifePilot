package com.lifepilot.media;

/**
 * 媒体校验异常。
 * <p>
 * 当媒体内容不满足校验规则（如大小超限、MIME 类型不支持等）时抛出。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class MediaValidationException extends RuntimeException {

    private final String reason;

    /**
     * 构造媒体校验异常。
     *
     * @param reason 校验失败的具体原因
     */
    public MediaValidationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * 获取校验失败原因。
     *
     * @return 失败原因描述
     */
    public String getReason() {
        return reason;
    }
}
