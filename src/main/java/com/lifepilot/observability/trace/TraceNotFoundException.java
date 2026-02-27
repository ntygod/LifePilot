package com.lifepilot.observability.trace;

/**
 * Trace 不存在异常。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceNotFoundException extends RuntimeException {

    public TraceNotFoundException(String traceId) {
        super("Trace 不存在: traceId=" + traceId);
    }
}
