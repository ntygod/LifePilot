package com.lifepilot.mcp.exception;

/**
 * MCP 服务器不可用异常。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class McpServerUnavailableException extends McpTransportException {

    private final String serverName;

    public McpServerUnavailableException(String serverName) {
        super("MCP Server 不可用: " + serverName);
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}
