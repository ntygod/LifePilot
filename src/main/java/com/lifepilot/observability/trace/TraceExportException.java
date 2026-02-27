package com.lifepilot.observability.trace;

/**
 * Trace 导出异常。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceExportException extends RuntimeException {

    public TraceExportException(String message) {
        super(message);
    }

    public TraceExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
