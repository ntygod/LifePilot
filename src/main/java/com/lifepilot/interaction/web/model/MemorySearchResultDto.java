package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

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
        float relevanceScore
) {}
