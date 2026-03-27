package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;

/**
 * 实体详情 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record EntityDetailDto(
        String id,
        String type,
        String typeLabel,
        String name,
        @Nullable String description,
        @Nullable String spaceId,
        @Nullable String memoryScope,
        @Nullable String realityType,
        Map<String, Object> properties,
        int version,
        boolean isCurrent,
        Instant validFrom,
        @Nullable Instant validTo,
        @Nullable String sourceConversationId,
        float extractionConfidence,
        float importanceScore,
        int accessCount,
        @Nullable Instant lastAccessedAt,
        Instant createdAt,
        Instant updatedAt
) {}
