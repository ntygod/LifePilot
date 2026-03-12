package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 实体列表项 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record EntitySummaryDto(
        String id,
        String type,
        String typeLabel,
        String name,
        @Nullable String description,
        float importanceScore,
        int accessCount,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
