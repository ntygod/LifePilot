package com.lifepilot.mcp.exception;

/**
 * MCP 工具调用异常。
 *
 * <p>MCP Server 返回的工具调用错误。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpToolCallException extends RuntimeException {

    public McpToolCallException(String message) {
        super(message);
    }

    public McpToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
