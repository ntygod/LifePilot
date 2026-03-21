package com.lifepilot.agent.model;

import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 请求 record。
 *
 * <p>支持普通对话请求和 SubAgent 委托请求。SubAgent 场景下可指定
 * 独立 System Prompt、预算、偏好 Provider 和工具白名单。</p>
 *
 * <p>Phase 2：图片多模态支持
 * 通过 {@code mediaContents} 字段携带当前请求关联的媒体内容（如图片），
 * 便于在 AgentLoop 内部根据场景路由到 {@code MultimodalRouter}。</p>
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
        @Nullable List<String> allowedToolIds,
        @Nullable List<MediaContent> mediaContents,
        @Nullable Double temperature,
        ResumePolicy resumePolicy
) {

    public AgentRequest {
        resumePolicy = resumePolicy != null ? resumePolicy : ResumePolicy.AUTO;
    }

    public AgentRequest(String message,
                        String sessionId,
                        String channel,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature) {
        this(message, sessionId, channel, systemPrompt, budget, parentTraceId, depth,
                preferredProvider, allowedToolIds, mediaContents, temperature, ResumePolicy.AUTO);
    }

    /**
     * 普通请求便捷构造器。
     *
     * @param message   用户消息
     * @param sessionId 会话 ID
     * @param channel   渠道标识
     */
    public AgentRequest(String message, String sessionId, String channel) {
        this(message, sessionId, channel, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO);
    }
}
