package com.lifepilot.agent.learning.staleness;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于向量相似度的 StaleConflictDetector 实现。
 *
 * <p>流程：类型白名单 → 描述非空 → VectorSearcher top-K → 排除自身 → 过滤 lifecycle/trust
 * → 按相似度排序取前 N 个。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class VectorBasedStaleConflictDetector implements StaleConflictDetector {

    private static final int MIN_DESCRIPTION_LENGTH = 4;

    private final VectorSearcher vectorSearcher;
    private final SemanticMemory semanticMemory;
    private final AgentLearningProperties.Staleness config;

    public VectorBasedStaleConflictDetector(VectorSearcher vectorSearcher,
                                            SemanticMemory semanticMemory,
                                            AgentLearningProperties.Staleness config) {
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "VectorSearcher 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "SemanticMemory 不能为空");
        this.config = Objects.requireNonNull(config, "Staleness 配置不能为空");
    }

    @Override
    public List<TemporalEntity> findStaleNeighbors(TemporalEntity newEntity) {
        Objects.requireNonNull(newEntity, "新实体不能为空");
        String newEntityId = requireCleanText(newEntity.id(), "staleness 新实体 id 不能为空");
        requireCleanText(newEntity.name(), "staleness 新实体 name 不能为空");
        EntityType newEntityType = Objects.requireNonNull(newEntity.type(), "staleness 新实体 type 不能为空");

        // 1. 类型白名单
        String entityType = newEntityType.name();
        var detectableTypes = requireDetectableTypes(config.getDetectableTypes());
        if (!detectableTypes.contains(entityType)) {
            return List.of();
        }

        // 2. 描述非空
        String query = buildQueryText(newEntity);
        if (query == null || query.length() < MIN_DESCRIPTION_LENGTH) {
            return List.of();
        }

        // 3. VectorSearcher top-K（多取几个用于过滤后仍够数）
        int maxNeighbors = positive(config.getMaxNeighborsPerDetection(), "staleness 单次邻居上限");
        int topK = maxNeighbors * 3;
        float threshold = probability(config.getDetectionSimilarityThreshold(), "staleness 相似度阈值");
        List<VectorSearchResult> candidates = vectorSearcher.searchEntities(query, topK, threshold);
        if (candidates == null) {
            throw new IllegalStateException("staleness 向量检索结果不能为空");
        }

        if (candidates.isEmpty()) return List.of();

        // 4. 排除自身 + 过滤 lifecycle/trust
        List<ScoredNeighbor> filtered = new ArrayList<>();
        for (VectorSearchResult vr : candidates) {
            if (vr == null) {
                throw new IllegalStateException("staleness 向量候选不能为空");
            }
            String entityId = requireCleanText(vr.entityId(), "staleness 向量候选 entityId 不能为空");
            float similarity = probability(vr.similarity(), "staleness 向量候选 similarity");
            if (entityId.equals(newEntityId)) continue;
            Optional<TemporalEntity> maybe = semanticMemory.findById(entityId);
            if (maybe == null) {
                throw new IllegalStateException("SemanticMemory.findById 返回值不能为空");
            }
            if (maybe.isEmpty()) {
                throw new IllegalStateException("staleness 向量候选实体不存在: " + entityId);
            }
            TemporalEntity neighbor = maybe.get();
            String neighborId = requireCleanText(neighbor.id(), "staleness 邻居实体 id 不能为空");
            if (!entityId.equals(neighborId)) {
                throw new IllegalStateException(
                        "staleness 向量候选实体不匹配: candidateId=%s, entityId=%s"
                                .formatted(entityId, neighborId));
            }
            if (neighbor.lifecycleState() == null) {
                throw new IllegalStateException("staleness 邻居生命周期不能为空: " + neighborId);
            }
            if (neighbor.lifecycleState() != LifecycleState.ACTIVE) continue;
            if (isBelowInferred(neighbor.trustLevel())) continue;
            filtered.add(new ScoredNeighbor(neighbor, similarity));
        }

        filtered.sort(Comparator.comparingDouble(n -> -n.similarity));
        int limit = Math.min(maxNeighbors, filtered.size());
        List<TemporalEntity> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) result.add(filtered.get(i).entity);
        return result;
    }

    private static boolean isBelowInferred(MemoryTrustLevel level) {
        Objects.requireNonNull(level, "邻居可信等级不能为空");
        return level == MemoryTrustLevel.UNVERIFIED;
    }

    private static java.util.Set<String> requireDetectableTypes(java.util.Set<String> types) {
        Objects.requireNonNull(types, "staleness detectableTypes 不能为空");
        if (types.isEmpty()) {
            throw new IllegalArgumentException("staleness detectableTypes 不能为空集合");
        }
        for (String type : types) {
            String value = requireCleanText(type, "staleness detectableTypes 元素不能为空");
            try {
                EntityType.valueOf(value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("staleness detectableTypes 包含未知实体类型: " + value, ex);
            }
        }
        return types;
    }

    private static String buildQueryText(TemporalEntity entity) {
        StringBuilder sb = new StringBuilder();
        if (entity.name() != null) sb.append(entity.name());
        if (entity.description() != null) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(entity.description());
        }
        return sb.toString().trim();
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }

    private static float probability(float value, String name) {
        if (!(value >= 0.0f && value <= 1.0f)) {
            throw new IllegalArgumentException(name + "必须在 [0,1] 范围内: " + value);
        }
        return value;
    }

    private static String requireCleanText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(message + "，且不能包含首尾空白: " + value);
        }
        return value;
    }

    private record ScoredNeighbor(TemporalEntity entity, float similarity) {}
}
