package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 关系列表项 DTO（附带实体名称和类型）。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record RelationDto(
        String id,
        String sourceEntityId,
        String sourceEntityName,
        String sourceEntityType,
        @Nullable String sourceEntitySpaceId,
        @Nullable String sourceEntityMemoryScope,
        @Nullable String sourceEntityRealityType,
        String targetEntityId,
        String targetEntityName,
        String targetEntityType,
        @Nullable String targetEntitySpaceId,
        @Nullable String targetEntityMemoryScope,
        @Nullable String targetEntityRealityType,
        String relationType,
        float strength,
        Instant validFrom,
        @Nullable Instant validTo,
        Instant createdAt
) {}
