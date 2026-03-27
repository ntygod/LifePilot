package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 实体去重合并器 — 定时检测并合并语义相同但名称不同的重复实体。
 *
 * <p>流程：获取所有当前实体 → 按 EntityType 分组 → 同类型内向量相似度检测
 * → 超过阈值的标记为重复候选 → 选择主实体 → 合并属性/关系 → 归档从实体。</p>
 *
 * @author zsg
 * @since 2026-03-15
 */
public class EntityDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(EntityDeduplicator.class);

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;

    public EntityDeduplicator(SemanticMemory semanticMemory,
                              VectorSearcher vectorSearcher,
                              JdbcTemplate jdbcTemplate,
                              MemoryProperties properties) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** 定时去重入口。 */
    @Scheduled(cron = "${lifepilot.memory.consolidation.dedup-cron}")
    public void scheduledDedup() {
        try {
            var stats = dedup();
            if (stats.mergedCount() > 0) {
                log.info("实体去重: 完成定时去重, 扫描={}, 候选={}, 合并={}, 耗时={}ms",
                        stats.entitiesScanned(), stats.candidatesFound(),
                        stats.mergedCount(), stats.elapsedMs());
            } else {
                log.debug("实体去重: 定时去重无合并, 扫描={}", stats.entitiesScanned());
            }
        } catch (Exception e) {
            log.error("实体去重: 定时去重异常, error={}", e.getMessage(), e);
        }
    }

    /**
     * 执行一次去重流程。
     *
     * @return 去重统计结果
     */
    public DedupStats dedup() {
        long startMs = System.currentTimeMillis();
        var allEntities = semanticMemory.findAllCurrent();
        int scanned = allEntities.size();
        if (scanned == 0) {
            return new DedupStats(0, 0, 0, System.currentTimeMillis() - startMs);
        }

        float threshold = properties.getConsolidation().getDedupSimilarityThreshold();
        int maxPerRun = properties.getConsolidation().getMaxDedupPerRun();

        // 按 EntityType 分组
        var grouped = allEntities.stream()
                .collect(Collectors.groupingBy(TemporalEntity::type));

        // 收集重复候选对（去重：用排序后的 ID 对作为 key）
        var candidates = new LinkedHashMap<String, CandidatePair>();
        Set<String> alreadyMerged = new HashSet<>();

        for (var entry : grouped.entrySet()) {
            var entities = entry.getValue();
            if (entities.size() < 2) continue;

            // 构建 ID → Entity 映射
            var entityMap = entities.stream()
                    .collect(Collectors.toMap(TemporalEntity::id, e -> e));

            for (var entity : entities) {
                if (alreadyMerged.contains(entity.id())) continue;
                try {
                    var results = vectorSearcher.searchEntities(
                            entity.textRepresentation(), 5, threshold);
                    for (VectorSearchResult result : results) {
                        String otherId = result.entityId();
                        if (otherId.equals(entity.id())) continue;
                        if (alreadyMerged.contains(otherId)) continue;
                        // 只匹配同类型实体
                        if (!entityMap.containsKey(otherId)) continue;

                        String pairKey = pairKey(entity.id(), otherId);
                        if (!candidates.containsKey(pairKey)) {
                            candidates.put(pairKey, new CandidatePair(
                                    entity, entityMap.get(otherId), result.similarity()));
                        }
                    }
                } catch (Exception e) {
                    log.warn("实体去重: 向量搜索异常, entityId={}, error={}",
                            entity.id(), e.getMessage());
                }
            }
        }

        // 执行合并（最多 maxPerRun 对）
        int mergedCount = 0;
        for (var pair : candidates.values()) {
            if (mergedCount >= maxPerRun) break;
            if (alreadyMerged.contains(pair.a.id()) || alreadyMerged.contains(pair.b.id())) {
                continue;
            }
            try {
                mergePair(pair);
                alreadyMerged.add(pair.secondary().id());
                mergedCount++;
            } catch (Exception e) {
                log.warn("实体去重: 单对合并失败, primary={}, secondary={}, error={}",
                        pair.primary().name(), pair.secondary().name(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        return new DedupStats(scanned, candidates.size(), mergedCount, elapsed);
    }

    /**
     * 合并一对重复实体：选择主实体 → 合并属性 → 迁移关系 → 归档从实体 → 记录日志。
     */
    private void mergePair(CandidatePair pair) {
        var primary = pair.primary();
        var secondary = pair.secondary();

        log.debug("实体去重: 开始合并, primary={}, secondary={}, similarity={}",
                primary.name(), secondary.name(), pair.similarity);

        // 1. 合并 description（主实体缺少时补充）
        String mergedDesc = primary.description();
        if ((mergedDesc == null || mergedDesc.isBlank())
                && secondary.description() != null && !secondary.description().isBlank()) {
            mergedDesc = secondary.description();
        }

        // 2. 增量合并 properties（主实体已有的 key 不覆盖）
        var mergedProps = new HashMap<>(secondary.properties());
        mergedProps.putAll(primary.properties());

        // 3. 累加 accessCount
        int mergedAccessCount = primary.accessCount() + secondary.accessCount();

        // 4. 构建合并后的主实体并更新
        var merged = new TemporalEntity(
                primary.id(), primary.type(), primary.name(), mergedDesc,
                mergedProps, primary.version(), primary.isCurrent(),
                primary.validFrom(), primary.validTo(), primary.sourceConversationId(),
                primary.extractionConfidence(), primary.importanceScore(),
                mergedAccessCount, primary.lastAccessedAt(),
                primary.createdAt(), Instant.now());
        semanticMemory.upsertWithConflictDetection(merged, merged.sourceConversationId());

        // 5. 迁移关系：将从实体的关系指向主实体
        try {
            migrateRelations(secondary.id(), primary.id());
        } catch (Exception e) {
            log.warn("实体去重: 关系迁移失败, secondaryId={}, error={}",
                    secondary.id(), e.getMessage());
        }

        // 6. 归档从实体
        semanticMemory.archive(secondary);

        // 7. 记录合并日志
        logMerge(primary.id(), secondary.id(), pair.similarity,
                "向量相似度超过阈值: " + primary.name() + " ≈ " + secondary.name());
    }

    /**
     * 迁移关系：将活动关系的实体引用更新为主实体 ID。
     */
    private void migrateRelations(String secondaryId, String primaryId) {
        int sourceUpdated = jdbcTemplate.update(
                "UPDATE memory_relations SET source_entity_id = ? WHERE source_entity_id = ? AND status = 'ACTIVE'",
                primaryId, secondaryId);
        int targetUpdated = jdbcTemplate.update(
                "UPDATE memory_relations SET target_entity_id = ? WHERE target_entity_id = ? AND status = 'ACTIVE'",
                primaryId, secondaryId);
        if (sourceUpdated + targetUpdated > 0) {
            log.debug("实体去重: 关系迁移完成, secondaryId={}, sourceUpdated={}, targetUpdated={}",
                    secondaryId, sourceUpdated, targetUpdated);
        }
    }

    /**
     * 记录合并日志到 entity_merge_log 表。
     */
    private void logMerge(String primaryId, String mergedId, float similarity, String reason) {
        jdbcTemplate.update(
                "INSERT INTO entity_merge_log(id, primary_entity_id, merged_entity_id, similarity_score, merge_reason, created_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), primaryId, mergedId,
                similarity, reason, Instant.now().toString());
    }

    /** 生成排序后的 ID 对 key，用于去重。 */
    private static String pairKey(String id1, String id2) {
        return id1.compareTo(id2) < 0 ? id1 + "|" + id2 : id2 + "|" + id1;
    }

    /**
     * 重复候选对 — 包含两个实体和相似度。
     * {@link #primary()} 返回主实体，{@link #secondary()} 返回从实体。
     */
    private record CandidatePair(TemporalEntity a, TemporalEntity b, float similarity) {
        /** 主实体选择：importanceScore 较高 → accessCount 较高 → createdAt 较早。 */
        TemporalEntity primary() {
            if (Float.compare(a.importanceScore(), b.importanceScore()) != 0) {
                return a.importanceScore() > b.importanceScore() ? a : b;
            }
            if (a.accessCount() != b.accessCount()) {
                return a.accessCount() > b.accessCount() ? a : b;
            }
            return a.createdAt().isBefore(b.createdAt()) ? a : b;
        }

        TemporalEntity secondary() {
            var p = primary();
            return p == a ? b : a;
        }
    }
}
