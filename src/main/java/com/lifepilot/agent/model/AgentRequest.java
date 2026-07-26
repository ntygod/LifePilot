package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 请求 record。
 *
 * <p>支持普通对话请求和 SubAgent 委托请求。SubAgent 场景下可指定独立
 * System Prompt、预算、偏好 Provider 和工具白名单。</p>
 *
 * <p>通过 {@code source + turnId + action} 标识请求来源与同一轮操作语义。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public record AgentRequest(
        String message,
        String sessionId,
        InteractionSource source,
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
        @Nullable List<String> disabledToolIds,
        @Nullable List<MediaContent> mediaContents,
        @Nullable Double temperature,
        @Nullable ResumePolicy resumePolicy,
        @Nullable List<String> overrideKnowledgeBaseIds,
        @Nullable String memoryContextMode,
        @Nullable Map<String, Object> turnRecoveryContext
) {

    public AgentRequest {
        source = source != null ? source : InteractionSource.system("unknown");
        action = action != null ? action : ChatTurnAction.SEND;
        taskMode = taskMode != null ? taskMode : AgentTaskMode.AUTO;
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
        disabledToolIds = disabledToolIds != null ? List.copyOf(disabledToolIds) : null;
        mediaContents = mediaContents != null ? List.copyOf(mediaContents) : null;
        resumePolicy = resumePolicy != null ? resumePolicy : ResumePolicy.AUTO;
        overrideKnowledgeBaseIds = overrideKnowledgeBaseIds != null ? List.copyOf(overrideKnowledgeBaseIds) : null;
        memoryContextMode = normalizeMemoryContextMode(memoryContextMode);
        turnRecoveryContext = copyMap(turnRecoveryContext);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
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
                        @Nullable ResumePolicy resumePolicy,
                        @Nullable List<String> overrideKnowledgeBaseIds,
                        @Nullable Map<String, Object> turnRecoveryContext) {
        this(message, sessionId, source, userId, turnId, action, taskMode, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, null, mediaContents,
                temperature, resumePolicy, overrideKnowledgeBaseIds, null, turnRecoveryContext);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
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
                        @Nullable ResumePolicy resumePolicy,
                        @Nullable List<String> overrideKnowledgeBaseIds,
                        @Nullable String memoryContextMode,
                        @Nullable Map<String, Object> turnRecoveryContext) {
        this(message, sessionId, source, userId, turnId, action, taskMode, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, null, mediaContents,
                temperature, resumePolicy, overrideKnowledgeBaseIds, memoryContextMode, turnRecoveryContext);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
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
                        @Nullable ResumePolicy resumePolicy,
                        @Nullable List<String> overrideKnowledgeBaseIds) {
        this(message, sessionId, source, userId, turnId, action, taskMode, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, null, mediaContents,
                temperature, resumePolicy, overrideKnowledgeBaseIds, null, null);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
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
        this(message, sessionId, source, userId, turnId, action, null, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, null, mediaContents,
                temperature, resumePolicy, null, null, null);
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
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), userId, turnId, action,
                null, systemPrompt, budget, parentTraceId, depth, preferredProvider,
                allowedToolIds, null, mediaContents, temperature, resumePolicy, null, null, null);
    }

    public AgentRequest(String message,
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
                        @Nullable ResumePolicy resumePolicy) {
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), userId, turnId, action,
                taskMode, systemPrompt, budget, parentTraceId, depth, preferredProvider,
                allowedToolIds, null, mediaContents, temperature, resumePolicy, null, null, null);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
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
        this(message, sessionId, source, userId, null, ChatTurnAction.SEND, taskMode, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, null, mediaContents,
                temperature, ResumePolicy.AUTO, null, null, null);
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
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), userId, null, ChatTurnAction.SEND,
                taskMode, systemPrompt, budget, parentTraceId, depth, preferredProvider,
                allowedToolIds, null, mediaContents, temperature, ResumePolicy.AUTO, null, null, null);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
                        @Nullable String userId,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature) {
        this(message, sessionId, source, userId, null, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, mediaContents,
                temperature);
    }

    public AgentRequest(String message,
                        String sessionId,
                        InteractionSource source,
                        @Nullable String userId,
                        @Nullable String systemPrompt,
                        @Nullable Budget budget,
                        @Nullable String parentTraceId,
                        int depth,
                        @Nullable String preferredProvider,
                        @Nullable List<String> allowedToolIds,
                        @Nullable List<String> disabledToolIds,
                        @Nullable List<MediaContent> mediaContents,
                        @Nullable Double temperature) {
        this(message, sessionId, source, userId, null, ChatTurnAction.SEND, AgentTaskMode.AUTO, systemPrompt, budget,
                parentTraceId, depth, preferredProvider, allowedToolIds, disabledToolIds, mediaContents,
                temperature, ResumePolicy.AUTO, null, null, null);
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
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), userId, null, systemPrompt, budget,
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
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), null, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO, null, null, null);
    }

    public AgentRequest(String message, String sessionId, InteractionSource source) {
        this(message, sessionId, source, null, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO, null, null, null);
    }

    public AgentRequest(String message, String sessionId, String channel, @Nullable String userId) {
        this(message, sessionId, InteractionSource.legacy(channel, sessionId), userId, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO, null, null, null);
    }

    public AgentRequest(String message, String sessionId, InteractionSource source, @Nullable String userId) {
        this(message, sessionId, source, userId, null, ChatTurnAction.SEND,
                AgentTaskMode.AUTO, null, null, null, 0, null, null, null, null, ResumePolicy.AUTO, null, null, null);
    }

    /**
     * 创建一个替换 message 的新请求副本，其他字段保持不变。
     *
     * @param newMessage 新的目标消息
     * @return 新的 AgentRequest 实例
     */
    public AgentRequest withMessage(String newMessage) {
        return new AgentRequest(newMessage, sessionId, source, userId, turnId, action, taskMode,
                systemPrompt, budget, parentTraceId, depth, preferredProvider, allowedToolIds,
                disabledToolIds, mediaContents, temperature, resumePolicy, overrideKnowledgeBaseIds,
                memoryContextMode, turnRecoveryContext);
    }

    /**
     * 创建一个替换 mediaContents 的新请求副本，其他字段保持不变。
     *
     * @param newMediaContents 新的媒体内容列表
     * @return 新的 AgentRequest 实例
     */
    public AgentRequest withMediaContents(@Nullable List<MediaContent> newMediaContents) {
        return new AgentRequest(message, sessionId, source, userId, turnId, action, taskMode,
                systemPrompt, budget, parentTraceId, depth, preferredProvider, allowedToolIds,
                disabledToolIds, newMediaContents, temperature, resumePolicy, overrideKnowledgeBaseIds,
                memoryContextMode, turnRecoveryContext);
    }

    public String channel() {
        return source.sourceId();
    }

    public SourceKind sourceKind() {
        return source.sourceKind();
    }

    @Nullable
    public String channelPlatform() {
        return source.channelPlatform();
    }

    @Nullable
    public String channelInstanceId() {
        return source.channelInstanceId();
    }

    @Nullable
    private static String normalizeMemoryContextMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "auto", "focused", "off" -> normalized;
            default -> null;
        };
    }

    @Nullable
    private static Map<String, Object> copyMap(@Nullable Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result.isEmpty() ? null : Map.copyOf(result);
    }
}
