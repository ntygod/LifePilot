package com.lifepilot.mcp.model;

/**
 * MCP 服务端信息。
 *
 * @param name 服务端名称
 * @param version 服务端版本
 * @author zsg
 * @since 2026-02-24
 */
public record McpServerInfo(
        String name,
        String version
) {}
