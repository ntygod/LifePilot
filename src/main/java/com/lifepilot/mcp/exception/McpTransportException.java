package com.lifepilot.mcp.exception;

/**
 * MCP 传输层异常。
 *
 * <p>在传输连接、消息发送/接收过程中发生的异常。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
