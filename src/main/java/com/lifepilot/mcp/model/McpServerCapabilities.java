package com.lifepilot.mcp.model;

/**
 * MCP 服务端能力。
 *
 * @param supportsTools 是否支持工具
 * @param supportsResources 是否支持资源
 * @param supportsPrompts 是否支持 Prompt 模板
 * @author zsg
 * @since 2026-02-24
 */
public record McpServerCapabilities(
        boolean supportsTools,
        boolean supportsResources,
        boolean supportsPrompts
) {}
