package com.lifepilot.memory.semantic;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import jakarta.annotation.Nullable;
import java.time.Instant;

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
    /** compact constructor：验证 strength 范围 + 补质量默认值。 */
    public TemporalRelation {
        if (strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException(
                    "关系强度必须在 [0.0, 1.0] 范围内，当前为 %.2f".formatted(strength));
        }
        evidenceKind = evidenceKind == null ? MemoryEvidenceKind.UNKNOWN : evidenceKind;
        trustLevel = trustLevel == null ? MemoryTrustLevel.UNVERIFIED : trustLevel;
        trustScore = Math.max(0.0f, Math.min(1.0f, trustScore));
    }

    /**
     * 便捷构造器 — 质量字段取默认（UNKNOWN/UNVERIFIED/0）。
     *
     * <p>供测试与未关心质量的写入点；按来源推导质量的写入口应改用全参构造器 + {@link #withQuality}。</p>
     */
    public TemporalRelation(String id, String sourceEntityId, String targetEntityId,
                            String relationType, float strength, @Nullable String propertiesJson,
                            Instant validFrom, @Nullable Instant validTo,
                            @Nullable String sourceConversationId, Instant createdAt) {
        this(id, sourceEntityId, targetEntityId, relationType, strength, propertiesJson,
                validFrom, validTo, sourceConversationId, createdAt,
                MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED, 0.0f);
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
