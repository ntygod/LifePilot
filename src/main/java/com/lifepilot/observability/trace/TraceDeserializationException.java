package com.lifepilot.observability.trace;

/**
 * Trace 步骤反序列化异常。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceDeserializationException extends RuntimeException {

    public TraceDeserializationException(String message) {
        super(message);
    }

    public TraceDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
