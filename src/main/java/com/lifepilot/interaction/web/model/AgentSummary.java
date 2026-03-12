package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;

/**
 * Agent 摘要信息。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record AgentSummary(
        String id,
        String name,
        String description,
        String type,
        String source,
        String preferredProviderId,
        int knowledgeBaseCount,
        Instant updatedAt,
        Instant createdAt,
        boolean enabled,
        String status,
        List<String> tags
) {}
