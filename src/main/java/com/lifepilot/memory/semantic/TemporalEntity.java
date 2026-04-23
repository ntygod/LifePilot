package com.lifepilot.memory.semantic;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 时序实体 — 知识图谱节点，版本化 + 时间维度 + 来源追踪。
 *
 * <p>V15 起额外承载生命周期闭环相关字段（{@code lifecycleState / lifecycleReason /
 * expiresAt / temporality / succeededBy / isDerived / derivationSources}），持久化
 * 到 {@code memory_entities} 基表上的 V15 新列。</p>
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
        List<String> derivationSources
) {
    /** compact constructor：确保集合字段不可变并为生命周期字段补默认值。 */
    public TemporalEntity {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        lifecycleState = lifecycleState == null ? LifecycleState.ACTIVE : lifecycleState;
        temporality = temporality == null ? Temporality.PERSISTENT : temporality;
        derivationSources = derivationSources == null ? List.of() : List.copyOf(derivationSources);
    }

    /**
     * 兼容老调用点的 16 参构造器 — 生命周期字段全部取默认值
     * （{@code ACTIVE} / {@code PERSISTENT} / 非派生 / 空 derivationSources）。
     *
     * <p>新代码建议直接使用 23 参 canonical constructor 或通过 {@link #withLifecycleState}
     * / {@link #withDescription} 变更副本。</p>
     */
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
                null, false, List.of());
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

    /**
     * 构造仅修改描述的新版本实体 — 便于 Task 10 走版本化路径调整描述字段。
     *
     * @param newDescription 新描述
     * @return 仅 description 变更的新实例
     */
    public TemporalEntity withDescription(@Nullable String newDescription) {
        return new TemporalEntity(
                id, type, name, newDescription, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                lifecycleState, lifecycleReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources
        );
    }

    /**
     * 构造仅修改生命周期状态 + 原因的新实例 — 便于 Task 12 事件总线内存视图更新。
     *
     * @param newState  新的生命周期状态
     * @param newReason 变更原因（可为 null）
     * @return 仅生命周期字段变更的新实例
     */
    public TemporalEntity withLifecycleState(LifecycleState newState, @Nullable String newReason) {
        return new TemporalEntity(
                id, type, name, description, properties, version, isCurrent,
                validFrom, validTo, sourceConversationId,
                extractionConfidence, importanceScore, accessCount, lastAccessedAt,
                createdAt, updatedAt,
                newState, newReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources
        );
    }
}
