package com.lifepilot.interaction.web.model;

import com.lifepilot.mcp.registry.McpServerState;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * MCP Server 状态变化 DTO（用于 SSE 增量推送）。
 *
 * @param serverName 服务器名称
 * @param oldState   旧状态
 * @param newState   新状态
 * @param timestamp  状态变化时间戳
 * @param error      错误信息（可为空）
 * @author zsg
 * @since 2026-03-11
 */
public record McpStatusChange(
        String serverName,
        McpServerState oldState,
        McpServerState newState,
        Instant timestamp,
        @Nullable String error
) {}
