package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 统一记忆搜索结果 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record MemorySearchResultDto(
        String entityId,
        String entityType,
        String name,
        @Nullable String description,
        float relevanceScore,
        @Nullable String spaceId,
        @Nullable String memoryScope,
        @Nullable String realityType,
        String lifecycleState,
        boolean historical,
        boolean stale,
        boolean needsRevalidation,
        String evidenceKind,
        String trustLevel,
        float trustScore,
        int evidenceCount,
        @Nullable Instant lastVerifiedAt,
        Map<String, Float> scoreBreakdown
) {}
