package com.lifepilot.agent.learning.staleness;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private static final Logger log = LoggerFactory.getLogger(VectorBasedStaleConflictDetector.class);
    private static final int MIN_DESCRIPTION_LENGTH = 4;

    private final VectorSearcher vectorSearcher;
    private final SemanticMemory semanticMemory;
    private final AgentLearningProperties.Staleness config;

    public VectorBasedStaleConflictDetector(VectorSearcher vectorSearcher,
                                            SemanticMemory semanticMemory,
                                            AgentLearningProperties.Staleness config) {
        this.vectorSearcher = vectorSearcher;
        this.semanticMemory = semanticMemory;
        this.config = config;
    }

    @Override
    public List<TemporalEntity> findStaleNeighbors(TemporalEntity newEntity) {
        if (newEntity == null) return List.of();

        // 1. 类型白名单
        String entityType = newEntity.type() == null ? "" : newEntity.type().name();
        if (!config.getDetectableTypes().contains(entityType)) {
            return List.of();
        }

        // 2. 描述非空
        String query = buildQueryText(newEntity);
        if (query == null || query.length() < MIN_DESCRIPTION_LENGTH) {
            return List.of();
        }

        // 3. VectorSearcher top-K（多取几个用于过滤后仍够数）
        int topK = Math.max(config.getMaxNeighborsPerDetection() * 3, 5);
        float threshold = config.getDetectionSimilarityThreshold();
        List<VectorSearchResult> candidates;
        try {
            candidates = vectorSearcher.searchEntities(query, topK, threshold);
        } catch (RuntimeException e) {
            log.warn("Staleness detect 向量检索失败: entity={}, err={}",
                    newEntity.id(), e.getMessage());
            return List.of();
        }

        if (candidates.isEmpty()) return List.of();

        // 4. 排除自身 + 过滤 lifecycle/trust
        List<ScoredNeighbor> filtered = new ArrayList<>();
        for (VectorSearchResult vr : candidates) {
            if (vr.entityId() == null || vr.entityId().equals(newEntity.id())) continue;
            Optional<TemporalEntity> maybe;
            try {
                maybe = semanticMemory.findById(vr.entityId());
            } catch (RuntimeException e) {
                log.debug("findById 失败: id={}, err={}", vr.entityId(), e.getMessage());
                continue;
            }
            if (maybe.isEmpty()) continue;
            TemporalEntity neighbor = maybe.get();
            if (neighbor.lifecycleState() != LifecycleState.ACTIVE) continue;
            if (isBelowInferred(neighbor.trustLevel())) continue;
            filtered.add(new ScoredNeighbor(neighbor, vr.similarity()));
        }

        filtered.sort(Comparator.comparingDouble(n -> -n.similarity));
        int limit = Math.min(config.getMaxNeighborsPerDetection(), filtered.size());
        List<TemporalEntity> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) result.add(filtered.get(i).entity);
        return result;
    }

    private static boolean isBelowInferred(MemoryTrustLevel level) {
        if (level == null) return true;
        return level == MemoryTrustLevel.UNVERIFIED;
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

    private record ScoredNeighbor(TemporalEntity entity, float similarity) {}
}
