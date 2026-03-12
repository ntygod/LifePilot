package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 对话列表项 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record ConversationSummaryDto(
        String id,
        String sessionId,
        String goal,
        @Nullable String summary,
        int messageCount,
        Instant createdAt
) {}
