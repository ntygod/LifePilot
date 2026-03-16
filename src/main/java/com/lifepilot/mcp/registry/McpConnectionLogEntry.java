package com.lifepilot.mcp.registry;

import java.time.Instant;

/**
 * MCP Server 连接日志条目。
 *
 * <p>记录 MCP Server 状态变化事件，用于前端连接日志时间线展示。</p>
 *
 * @param timestamp   事件时间戳
 * @param eventType   事件类型（CONNECT / DISCONNECT / ERROR / RECONNECT）
 * @param description 事件描述
 * @author zsg
 * @since 2026-03-16
 */
public record McpConnectionLogEntry(
        Instant timestamp,
        String eventType,
        String description
) {}
