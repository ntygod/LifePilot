package com.lifepilot.interaction.web.model;

import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Non-streaming chat response payload used by both sendMessage and A2UI signal reply.
 */
public record ChatResponse(
        String messageId,
        String content,
        @Nullable List<A2uiComponent> a2uiComponents,
        @Nullable TokenUsage tokenUsage,
        @Nullable String traceId
) {
}
