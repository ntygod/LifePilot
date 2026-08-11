package com.lifepilot.agent.intelligence.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 工具健康状态 — 基于滑动窗口的执行统计。
 *
 * @author zsg
 * @since 2026-06-01
 */
public record ToolHealth(
    String toolId,
    int recentSuccesses,
    int recentFailures,
    long avgLatencyMs,
    @Nullable
    String lastError,
    @Nullable
    Instant lastExecutedAt
) {
    public float successRate() {
        int total = recentSuccesses + recentFailures;
        return total == 0 ? 1.0f : (float) recentSuccesses / total;
    }

    public boolean isHealthy() {
        return successRate() >= 0.5f;
    }

    public static ToolHealth unknown(String toolId) {
        return new ToolHealth(toolId, 0, 0, 0L, null, null);
    }
}
