package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 非流式聊天响应载荷。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record ChatResponse(
        String entryId,
        @Nullable String turnId,
        AgentTaskMode taskMode,
        String content,
        @Nullable List<A2uiComponent> a2uiComponents,
        @Nullable TokenUsage tokenUsage,
        @Nullable String traceId,
        CompletionMode completionMode,
        @Nullable CompletionReason completionReason,
        @Nullable String resumedFromTraceId,
        @Nullable ChatTurnStatus turnStatus,
        @Nullable List<Map<String, Object>> toolsSummary,
        @Nullable Map<String, Object> taskRecovery,
        @Nullable Map<String, Object> executionConstraints
) {
    public ChatResponse {
        toolsSummary = toolsSummary != null ? List.copyOf(toolsSummary) : null;
        taskRecovery = taskRecovery != null ? Map.copyOf(taskRecovery) : null;
        executionConstraints = executionConstraints != null ? Map.copyOf(executionConstraints) : null;
    }

    public ChatResponse(String entryId,
                        @Nullable String turnId,
                        AgentTaskMode taskMode,
                        String content,
                        @Nullable List<A2uiComponent> a2uiComponents,
                        @Nullable TokenUsage tokenUsage,
                        @Nullable String traceId,
                        CompletionMode completionMode,
                        @Nullable CompletionReason completionReason,
                        @Nullable String resumedFromTraceId,
                        @Nullable ChatTurnStatus turnStatus) {
        this(entryId, turnId, taskMode, content, a2uiComponents, tokenUsage, traceId,
                completionMode, completionReason, resumedFromTraceId, turnStatus, null, null, null);
    }

    public ChatResponse(String entryId,
                        @Nullable String turnId,
                        String content,
                        @Nullable List<A2uiComponent> a2uiComponents,
                        @Nullable TokenUsage tokenUsage,
                        @Nullable String traceId) {
        this(entryId, turnId, AgentTaskMode.AUTO, content, a2uiComponents, tokenUsage, traceId,
                CompletionMode.NORMAL, null, null, ChatTurnStatus.SUCCESS, null, null, null);
    }
}
