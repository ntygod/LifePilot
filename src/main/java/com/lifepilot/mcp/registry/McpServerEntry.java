package com.lifepilot.mcp.registry;

import com.lifepilot.mcp.McpClient;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.model.McpServerInfo;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * MCP Server 条目 — 运行时状态快照。
 *
 * @param config 服务器配置
 * @param client MCP 客户端实例
 * @param state 当前连接状态
 * @param serverInfo 服务端信息（初始化后填充）
 * @param lastHealthCheck 上次健康检查时间
 * @param reconnectAttempts 当前重连尝试次数
 * @param connectedSince 连接建立时间
 * @param lastError 最近一次错误信息
 * @param lastToolCall 上次工具调用时间（空闲超时检测用）
 * @param maintenanceFuture 维护任务句柄（健康检查 + 空闲检测合并 tick）
 * @author zsg
 * @since 2026-02-24
 */
@Builder(toBuilder = true)
public record McpServerEntry(
        McpServerConfig config,
        @Nullable McpClient client,
        McpServerState state,
        @Nullable McpServerInfo serverInfo,
        @Nullable Instant lastHealthCheck,
        int reconnectAttempts,
        @Nullable Instant connectedSince,
        @Nullable String lastError,
        @Nullable Instant lastToolCall,
        @Nullable ScheduledFuture<?> maintenanceFuture
) {

    /** 创建初始条目（未连接状态）。 */
    public static McpServerEntry initial(McpServerConfig config) {
        return McpServerEntry.builder()
                .config(config)
                .state(McpServerState.DISCONNECTED)
                .reconnectAttempts(0)
                .build();
    }
}
