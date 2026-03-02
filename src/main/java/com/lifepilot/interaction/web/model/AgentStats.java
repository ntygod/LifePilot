package com.lifepilot.interaction.web.model;

/**
 * Analytics Agent 统计响应。
 *
 * @param agentId          Agent ID
 * @param agentName        Agent 名称
 * @param callCount        调用次数
 * @param avgResponseTime  平均响应时间（毫秒）
 * @param failureRate      失败率（0.0-1.0）
 * @param totalTokens      总 Token 数
 * @author zsg
 * @since 2026-02-28
 */
public record AgentStats(
        String agentId,
        String agentName,
        long callCount,
        long avgResponseTime,
        double failureRate,
        long totalTokens
) {
}
