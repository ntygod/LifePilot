package com.lifepilot.memory.semantic;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
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
    /** compact constructor：确保集合字段不可变，并补齐生命周期与质量默认值。 */
    public TemporalEntity {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        lifecycleState = lifecycleState == null ? LifecycleState.ACTIVE : lifecycleState;
        temporality = temporality == null ? Temporality.PERSISTENT : temporality;
        derivationSources = derivationSources == null ? List.of() : List.copyOf(derivationSources);
        evidenceKind = evidenceKind == null ? MemoryEvidenceKind.UNKNOWN : evidenceKind;
        trustLevel = trustLevel == null ? MemoryTrustLevel.UNVERIFIED : trustLevel;
        trustScore = Math.max(0.0f, Math.min(1.0f, trustScore));
        evidenceCount = Math.max(0, evidenceCount);
    }

    /** 基础构造器 — 生命周期与质量字段取默认值。 */
    public TemporalEntity(
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
        this(id, type, name, description, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                null, false, List.of(),
                MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED,
                0.0f, 0, null);
    }

    /** 生命周期构造器 — 质量字段取默认值，由 SemanticMemory 统一补全。 */
    public TemporalEntity(
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
            List<String> derivationSources
    ) {
        this(id, type, name, description, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                lifecycleState, lifecycleReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources,
                MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED,
                0.0f, 0, null);
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
