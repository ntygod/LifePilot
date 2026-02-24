package com.lifepilot.memory.semantic;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;

/**
 * 时序实体 — 知识图谱节点，版本化 + 时间维度 + 来源追踪。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TemporalEntity(
        String id,
        EntityType type,
        String name,
        @Nullable String description,
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
) {
    /** compact constructor：确保 properties 不可变。 */
    public TemporalEntity {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }

    /** 拼接 name + description + properties 关键值为文本，用于向量化。 */
    public String textRepresentation() {
        var sb = new StringBuilder(name);
        if (description != null && !description.isBlank()) {
            sb.append(" ").append(description);
        }
        properties.forEach((k, v) -> sb.append(" ").append(k).append(":").append(v));
        return sb.toString();
    }

    /** 当前有效：isCurrent 为 true 且 validTo 为 null。 */
    public boolean isActive() {
        return isCurrent && validTo == null;
    }
}
