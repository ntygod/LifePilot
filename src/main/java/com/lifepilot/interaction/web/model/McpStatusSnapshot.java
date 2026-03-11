package com.lifepilot.interaction.web.model;

import com.lifepilot.mcp.registry.McpServerState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * MCP Server 状态快照 DTO（用于 SSE 初始推送）。
 *
 * @param servers 所有已注册 MCP Server 的当前状态列表
 * @author zsg
 * @since 2026-03-11
 */
public record McpStatusSnapshot(List<ServerStatus> servers) {

    /**
     * 单个 MCP Server 的状态摘要。
     *
     * @param serverName     服务器名称
     * @param state          当前连接状态
     * @param connectedSince 连接建立时间（未连接时为 null）
     * @param lastError      最近一次错误信息（无错误时为 null）
     */
    public record ServerStatus(
            String serverName,
            McpServerState state,
            @Nullable Instant connectedSince,
            @Nullable String lastError
    ) {}
}
