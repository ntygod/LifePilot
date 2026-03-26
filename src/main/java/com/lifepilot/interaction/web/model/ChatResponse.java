package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.lang.Nullable;

import java.util.List;

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
        @Nullable ChatTurnStatus turnStatus
) {
    public ChatResponse(String entryId,
                        @Nullable String turnId,
                        String content,
                        @Nullable List<A2uiComponent> a2uiComponents,
                        @Nullable TokenUsage tokenUsage,
                        @Nullable String traceId) {
        this(entryId, turnId, AgentTaskMode.AUTO, content, a2uiComponents, tokenUsage, traceId,
                CompletionMode.NORMAL, null, null, ChatTurnStatus.SUCCESS);
    }
}
