package com.lifepilot.memory.store.entity;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Instant updatedAt,
        LifecycleState lifecycleState,
        @Nullable String lifecycleReason,
        @Nullable Instant expiresAt,
        Temporality temporality,
        @Nullable String succeededBy,
        boolean isDerived,
        List<String> derivationSources,
        MemoryEvidenceKind evidenceKind,
        MemoryTrustLevel trustLevel,
        float trustScore,
        int evidenceCount,
        @Nullable Instant lastVerifiedAt
) {
    /** compact constructor：确保集合字段不可变，并校验质量字段。 */
    public TemporalEntity {
        Objects.requireNonNull(type, "实体类型不能为空");
        Objects.requireNonNull(name, "实体名称不能为空");
        if (name.isBlank()) {
            throw new IllegalArgumentException("实体名称不能为空");
        }
        if (!name.equals(name.trim())) {
            throw new IllegalArgumentException("实体名称不能包含首尾空白");
        }
        Objects.requireNonNull(validFrom, "实体生效时间不能为空");
        Objects.requireNonNull(createdAt, "实体创建时间不能为空");
        Objects.requireNonNull(updatedAt, "实体更新时间不能为空");
        properties = Map.copyOf(Objects.requireNonNull(properties, "实体属性不能为空"));
        Objects.requireNonNull(lifecycleState, "实体生命周期状态不能为空");
        Objects.requireNonNull(temporality, "实体时效类型不能为空");
        derivationSources = List.copyOf(Objects.requireNonNull(derivationSources, "实体派生来源不能为空"));
        Objects.requireNonNull(evidenceKind, "实体证据类型不能为空");
        Objects.requireNonNull(trustLevel, "实体可信等级不能为空");
        if (!(trustScore >= 0.0f && trustScore <= 1.0f)) {
            throw new IllegalArgumentException("实体可信分必须在 [0,1] 范围内: " + trustScore);
        }
        if (evidenceCount < 0) {
            throw new IllegalArgumentException("实体证据数量不能为负数: " + evidenceCount);
        }
    }

    /** 拼接类型标签 + name + description + properties 为自然语言文本，用于向量化。 */
    public String textRepresentation() {
        var sb = new StringBuilder("[").append(type.label()).append("] ").append(name);
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        if (!properties.isEmpty()) {
            var propParts = properties.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .toList();
            sb.append("，").append(String.join("，", propParts));
        }
        return sb.toString();
    }

    /** 当前有效：isCurrent 为 true 且 validTo 为 null。 */
    public boolean isActive() {
        return isCurrent && validTo == null;
    }

    /** 判断实体是否已过期：validTo 非空且早于当前时间。 */
    public boolean isExpired() {
        return validTo != null && validTo.isBefore(Instant.now());
    }

    public TemporalEntity withDescription(@Nullable String newDescription) {
        return new TemporalEntity(
                id, type, name, newDescription, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                lifecycleState, lifecycleReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources,
                evidenceKind, trustLevel, trustScore, evidenceCount, lastVerifiedAt
        );
    }

    public TemporalEntity withLifecycleState(LifecycleState newState, @Nullable String newReason) {
        return new TemporalEntity(
                id, type, name, description, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                newState, newReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources,
                evidenceKind, trustLevel, trustScore, evidenceCount, lastVerifiedAt
        );
    }

    public TemporalEntity withQuality(MemoryEvidenceKind newEvidenceKind,
                                      MemoryTrustLevel newTrustLevel,
                                      float newTrustScore,
                                      int newEvidenceCount,
                                      @Nullable Instant newLastVerifiedAt) {
        return new TemporalEntity(
                id, type, name, description, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                lifecycleState, lifecycleReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources,
                newEvidenceKind, newTrustLevel, newTrustScore, newEvidenceCount, newLastVerifiedAt
        );
    }
}
