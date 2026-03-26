package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;

/**
 * 会话详细信息。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record SessionDetailInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Boolean pinned,
        Boolean archived,
        String preferredProviderId,
        Double temperature,
        Integer maxTokens,
        Integer maxSteps,
        Integer maxDurationSeconds,
        List<String> knowledgeBaseIds,
        Integer messageCount,
        Long totalTokens,
        String lastMessagePreview
) {}
