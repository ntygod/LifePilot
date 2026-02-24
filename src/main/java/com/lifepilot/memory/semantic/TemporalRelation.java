package com.lifepilot.memory.semantic;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * 时序关系 — 知识图谱的边，带强度和时间维度。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TemporalRelation(
        String id,
        String sourceEntityId,
        String targetEntityId,
        String relationType,
        float strength,
        @Nullable String propertiesJson,
        Instant validFrom,
        @Nullable Instant validTo,
        @Nullable String sourceConversationId,
        Instant createdAt
) {
    /** compact constructor：验证 strength 范围。 */
    public TemporalRelation {
        if (strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException(
                    "关系强度必须在 [0.0, 1.0] 范围内，当前为 %.2f".formatted(strength));
        }
    }

    /** 当前有效：validTo 为 null。 */
    public boolean isActive() {
        return validTo == null;
    }
}
