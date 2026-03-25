package com.lifepilot.agent.model;

import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 请求 record。
 *
 * <p>支持普通对话请求和 SubAgent 委托请求。SubAgent 场景下可指定独立
 * System Prompt、预算、偏好 Provider 和工具白名单。</p>
 *
 * <p>通过 {@code turnId + action} 标识同一轮的发送、重试、恢复和重开。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public record AgentRequest(
        String message,
        String sessionId,
        String channel,
        @Nullable String userId,
        @Nullable String turnId,
        @Nullable ChatTurnAction action,
        @Nullable AgentTaskMode taskMode,
        @Nullable String systemPrompt,
        @Nullable Budget budget,
        @Nullable String parentTraceId,
        int depth,
        @Nullable String preferredProvider,
        @Nullable List<String> allowedToolIds,
        @Nullable List<MediaContent> mediaContents,
        @Nullable Double temperature,
        @Nullable ResumePolicy resumePolicy
) {

    public AgentRequest {
        action = action != null ? action : ChatTurnAction.SEND;
        taskMode = taskMode != null ? taskMode : AgentTaskMode.AUTO;
        resumePolicy = resumePolicy != null ? resumePolicy : ResumePolicy.AUTO;
    }

    public AgentRequest(String message,
                        String sessionId,
                        String channel,
                        @Nullable String userId,
                        @Nullable String turnId,
                        @Nullable ChatTurnAction action,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature,
                        @Nullable ResumePolicy resumePolicy) {
        this(message, sessionId, channel, userId, turnId, action, null, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, mediaContents,
                temperature, resumePolicy);
    }

    public AgentRequest(String message,
                        String sessionId,
                        String channel,
                        @Nullable String userId,
                        @Nullable AgentTaskMode taskMode,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature) {
        this(message, sessionId, channel, userId, null, ChatTurnAction.SEND, taskMode, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, mediaContents,
                temperature, ResumePolicy.AUTO);
    }

    public AgentRequest(String message,
                        String sessionId,
                        String channel,
                        @Nullable String userId,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature) {
        this(message, sessionId, channel, userId, null, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, mediaContents,
                temperature);
    }

    /**
     * 普通请求便捷构造器。
     *
     * @param message 用户消息
     * @param sessionId 会话 ID
     * @param channel 渠道标识
     */
    public AgentRequest(String message, String sessionId, String channel) {
        this(message, sessionId, channel, null, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO);
    }

    public AgentRequest(String message, String sessionId, String channel, @Nullable String userId) {
        this(message, sessionId, channel, userId, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO);
    }
}
