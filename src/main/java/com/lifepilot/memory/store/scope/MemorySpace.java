package com.lifepilot.memory.store.scope;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆空间。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record MemorySpace(
        String id,
        String spaceKey,
        MemorySpaceType spaceType,
        String displayName,
        @Nullable String ownerType,
        @Nullable String ownerId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public MemorySpace {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
