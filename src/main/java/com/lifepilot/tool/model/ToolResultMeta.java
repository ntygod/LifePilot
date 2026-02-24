package com.lifepilot.tool.model;

import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Duration;
import java.time.Instant;

/**
 * 工具执行轨迹元信息。
 *
 * <p>每次工具调用都会生成一个 ToolResultMeta，
 * 与 Agent 引擎的 TraceStep 关联，形成完整的决策轨迹。</p>
 *
 * @param toolId 工具 ID
 * @param action 执行的操作
 * @param duration 执行耗时
 * @param tokensUsed 消耗的 Token 数
 * @param cacheHit 是否命中幂等缓存
 * @param idempotencyKey 幂等键
 * @param retryCount 实际重试次数
 * @param executorType 执行器类型：BUILTIN / YAML / MCP
 * @param mcpServerName MCP 服务器名称（如果是 MCP 工具）
 * @param timestamp 执行时间戳
 * @author zsg
 * @since 2026-02-24
 */
@Builder(toBuilder = true)
public record ToolResultMeta(
        String toolId,
        String action,
        Duration duration,
        int tokensUsed,
        boolean cacheHit,
        @Nullable String idempotencyKey,
        int retryCount,
        String executorType,
        @Nullable String mcpServerName,
        Instant timestamp
) {
    /** 空元信息（用于测试和简单场景）。 */
    public static ToolResultMeta empty() {
        return new ToolResultMeta(
                "", "", Duration.ZERO, 0, false, null, 0, "UNKNOWN", null, Instant.now()
        );
    }
}
