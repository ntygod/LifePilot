package com.lifepilot.agent.model;

/**
 * Agent 请求 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AgentRequest(
        String message,
        String sessionId,
        String channel
) {
}
