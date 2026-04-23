package com.lifepilot.memory.semantic;

import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 记忆实体精简视图 — 聚焦于基表 {@code memory_entities} + 当前版本 {@code memory_entity_versions} 的
 * 核心字段，含 V15 生命周期闭环相关新字段（lifecycle / temporality / 派生关系）。
 *
 * <p>与 {@link TemporalEntity} 并存：前者是测试断言与生命周期逻辑使用的精简模型，后者是现有语义记忆
 * 服务内部完整快照。save() / findById 在基表上直接读写，不走 {@code temporal_entities} 兼容视图，
 * 以便承载 V15 新列。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public record MemoryEntity(
        String id,
        String type,
        String name,
        @Nullable String description,
        double importanceScore,
        LifecycleState lifecycleState,
        @Nullable String lifecycleReason,
        @Nullable Instant expiresAt,
        Temporality temporality,
        @Nullable String succeededBy,
        boolean isDerived,
        List<String> derivationSources
) {

    /** 紧凑构造器：对枚举和集合字段做默认值 + 防御拷贝。 */
    public MemoryEntity {
        lifecycleState = lifecycleState == null ? LifecycleState.ACTIVE : lifecycleState;
        temporality = temporality == null ? Temporality.PERSISTENT : temporality;
        derivationSources = derivationSources == null ? List.of() : List.copyOf(derivationSources);
    }

    /**
     * 构造仅修改描述的新版本实体 — 用于 {@code SemanticMemory.updateDescription} 走版本化路径（修补 A）。
     *
     * @param newDescription 新描述
     * @return 仅 description 变更的新实例
     */
    public MemoryEntity withDescription(String newDescription) {
        return new MemoryEntity(
                id, type, name, newDescription, importanceScore,
                lifecycleState, lifecycleReason, expiresAt, temporality,
                succeededBy, isDerived, derivationSources
        );
    }
}
