package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 非流式聊天响应载荷，供 sendMessage 和 A2UI 信号回复共用。
 *
 * @author zsg
 * @since 2026-02-26
 */
public record ChatResponse(
        String messageId,
        String content,
        @Nullable List<A2uiComponent> a2uiComponents,
        @Nullable TokenUsage tokenUsage,
        @Nullable String traceId,
        CompletionMode completionMode,
        @Nullable String resumedFromTraceId
) {
    public ChatResponse(String messageId,
                        String content,
                        @Nullable List<A2uiComponent> a2uiComponents,
                        @Nullable TokenUsage tokenUsage,
                        @Nullable String traceId) {
        this(messageId, content, a2uiComponents, tokenUsage, traceId, CompletionMode.NORMAL, null);
    }
}
