package com.lifepilot.mcp.exception;

/**
 * MCP 连接超时异常。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpConnectionTimeoutException extends McpTransportException {

    public McpConnectionTimeoutException(String serverName) {
        super("MCP Server 连接超时: " + serverName);
    }
}
