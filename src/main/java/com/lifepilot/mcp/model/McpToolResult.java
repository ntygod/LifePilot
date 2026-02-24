package com.lifepilot.mcp.model;

import java.util.List;

/**
 * MCP 工具调用结果。
 *
 * @param content 结果内容列表
 * @param isError 是否为错误结果
 * @author zsg
 * @since 2026-02-24
 */
public record McpToolResult(
        List<McpContent> content,
        boolean isError
) {
    public McpToolResult {
        content = List.copyOf(content);
    }
}
