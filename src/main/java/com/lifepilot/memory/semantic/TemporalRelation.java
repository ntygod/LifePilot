package com.lifepilot.memory.semantic;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * 时序关系 — 知识图谱的边，带强度、时间维度与质量治理字段。
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
        Instant createdAt,
        MemoryEvidenceKind evidenceKind,
        MemoryTrustLevel trustLevel,
        float trustScore
) {
    /** compact constructor：验证 strength 与质量字段。 */
    public TemporalRelation {
        Objects.requireNonNull(sourceEntityId, "关系源实体不能为空");
        Objects.requireNonNull(targetEntityId, "关系目标实体不能为空");
        Objects.requireNonNull(relationType, "关系类型不能为空");
        Objects.requireNonNull(validFrom, "关系生效时间不能为空");
        Objects.requireNonNull(createdAt, "关系创建时间不能为空");
        if (!(strength >= 0.0f && strength <= 1.0f)) {
            throw new IllegalArgumentException(
                    "关系强度必须在 [0.0, 1.0] 范围内，当前为 %.2f".formatted(strength));
        }
        Objects.requireNonNull(evidenceKind, "关系证据类型不能为空");
        Objects.requireNonNull(trustLevel, "关系可信等级不能为空");
        if (!(trustScore >= 0.0f && trustScore <= 1.0f)) {
            throw new IllegalArgumentException("关系可信分必须在 [0,1] 范围内: " + trustScore);
        }
    }

    /** 当前有效：validTo 为 null。 */
    public boolean isActive() {
        return validTo == null;
    }

    /** 返回带质量字段的副本。 */
    public TemporalRelation withQuality(MemoryEvidenceKind newEvidenceKind,
                                        MemoryTrustLevel newTrustLevel,
                                        float newTrustScore) {
        return new TemporalRelation(id, sourceEntityId, targetEntityId, relationType, strength,
                propertiesJson, validFrom, validTo, sourceConversationId, createdAt,
                newEvidenceKind, newTrustLevel, newTrustScore);
    }
}
