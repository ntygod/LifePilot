package com.lifepilot.agent.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 请求 record。
 *
 * <p>支持普通对话请求和 SubAgent 委托请求。SubAgent 场景下可指定
 * 独立 System Prompt、预算、偏好 Provider 和工具白名单。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AgentRequest(
        String message,
        String sessionId,
        String channel,
        @Nullable String systemPrompt,
        @Nullable Budget budget,
        @Nullable String parentTraceId,
        int depth,
        @Nullable String preferredProvider,
        @Nullable List<String> allowedToolIds
) {

    /**
     * 普通请求便捷构造器。
     *
     * @param message   用户消息
     * @param sessionId 会话 ID
     * @param channel   渠道标识
     */
    public AgentRequest(String message, String sessionId, String channel) {
        this(message, sessionId, channel, null, null, null, 0, null, null);
    }
}
