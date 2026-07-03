package com.lifepilot.memory.store.scope;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

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
        Objects.requireNonNull(id, "记忆空间 id 不能为空");
        Objects.requireNonNull(spaceKey, "记忆空间 key 不能为空");
        Objects.requireNonNull(spaceType, "记忆空间类型不能为空");
        Objects.requireNonNull(displayName, "记忆空间名称不能为空");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "记忆空间元数据不能为空"));
        Objects.requireNonNull(createdAt, "记忆空间创建时间不能为空");
        Objects.requireNonNull(updatedAt, "记忆空间更新时间不能为空");
    }
}
