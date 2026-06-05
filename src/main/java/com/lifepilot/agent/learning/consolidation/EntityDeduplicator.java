package com.lifepilot.agent.learning.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final JdbcTemplate jdbcTemplate;
    private final AgentLearningProperties properties;
    private final TransactionTemplate transactionTemplate;

    public EntityDeduplicator(SemanticMemory semanticMemory,
                              VectorSearcher vectorSearcher,
                              JdbcTemplate jdbcTemplate,
                              AgentLearningProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 定时去重入口。 */
    @Scheduled(cron = "${lifepilot.agent.learning.consolidation.dedup-cron}")
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
                try {
                    var results = vectorSearcher.searchEntities(
                            entity.textRepresentation(), 5, threshold);
                    for (VectorSearchResult result : results) {
                        String otherId = result.entityId();
                        if (otherId.equals(entity.id())) continue;
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
     * 整个合并过程在单一事务内执行，确保原子性。
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

        String finalDesc = mergedDesc;
        String propsJson = mergedProps.isEmpty() ? null : serializeProps(mergedProps);
        String now = Instant.now().toString();

        // 合并后 primary 是派生产物 — 本次合并的 primary + secondary ids 共同构成 derivation_sources，
        // 这样 DerivedEntityListener 能在任一源失效时通过 LIKE 反查命中该 primary 并触发级联。
        // 若 primary 已带 derivation_sources，采用"追加去重合并"：合并旧 + 新 ids。
        List<String> combinedSources = mergeDerivationSources(
                primary.derivationSources(), primary.id(), secondary.id());
        String derivationSourcesJson = serializeDerivationSources(combinedSources);

        // 事务包裹：更新主实体 + 迁移关系 + 归档从实体 + 记录日志，保证原子性
        SqliteBusyRetry.run(() -> transactionTemplate.executeWithoutResult(status -> {
            // 4. 更新主实体属性 — 同时标记为派生实体并写入 derivation_sources
            jdbcTemplate.update("""
                    UPDATE memory_entities
                    SET access_count = ?, last_seen_at = ?, updated_at = ?,
                        is_derived = 1, derivation_sources = ?
                    WHERE id = ?
                    """,
                    mergedAccessCount, now, now, derivationSourcesJson, primary.id());
            jdbcTemplate.update("""
                    UPDATE memory_entity_versions
                    SET description = COALESCE(?, description),
                        properties_json = COALESCE(?, properties_json),
                        updated_at = ?
                    WHERE entity_id = ? AND is_current = 1
                    """,
                    finalDesc, propsJson, now, primary.id());

            // 5. 迁移关系
            migrateRelations(secondary.id(), primary.id(), now);

            // 6. 归档从实体 — 去重合并属冲突裁决，事件 source=CONFLICT_RESOLVE
            semanticMemory.archive(secondary, ChangeSource.CONFLICT_RESOLVE);

            // 7. 记录合并日志
            logMerge(primary.id(), secondary.id(), pair.similarity,
                    "向量相似度超过阈值: " + primary.name() + " ≈ " + secondary.name());
        }));
    }

    /**
     * 迁移关系：归档冲突关系后，将剩余活动关系的实体引用更新为主实体 ID。
     *
     * <p>冲突场景：
     * <ul>
     *   <li>自引用 — secondary ↔ primary 的直接关系，迁移后 source = target</li>
     *   <li>重复 — primary 已有相同方向+类型+目标的关系</li>
     * </ul>
     */
    private void migrateRelations(String secondaryId, String primaryId, String now) {
        // 1. 归档自引用关系（secondary ↔ primary）
        jdbcTemplate.update("""
                UPDATE memory_relations SET status = 'ARCHIVED', updated_at = ?
                WHERE status = 'ACTIVE' AND (
                    (source_entity_id = ? AND target_entity_id = ?)
                    OR (source_entity_id = ? AND target_entity_id = ?)
                )""", now, secondaryId, primaryId, primaryId, secondaryId);

        // 2. 归档迁移后会与主实体现有关系重复的从实体关系（source 侧）
        jdbcTemplate.update("""
                UPDATE memory_relations SET status = 'ARCHIVED', updated_at = ?
                WHERE source_entity_id = ? AND status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM memory_relations r2
                      WHERE r2.source_entity_id = ?
                        AND r2.target_entity_id = memory_relations.target_entity_id
                        AND r2.relation_type = memory_relations.relation_type
                        AND r2.status = 'ACTIVE'
                  )""", now, secondaryId, primaryId);

        // 3. 归档重复关系（target 侧）
        jdbcTemplate.update("""
                UPDATE memory_relations SET status = 'ARCHIVED', updated_at = ?
                WHERE target_entity_id = ? AND status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM memory_relations r2
                      WHERE r2.target_entity_id = ?
                        AND r2.source_entity_id = memory_relations.source_entity_id
                        AND r2.relation_type = memory_relations.relation_type
                        AND r2.status = 'ACTIVE'
                  )""", now, secondaryId, primaryId);

        // 4. 迁移剩余关系
        int sourceUpdated = jdbcTemplate.update(
                "UPDATE memory_relations SET source_entity_id = ?, updated_at = ? WHERE source_entity_id = ? AND status = 'ACTIVE'",
                primaryId, now, secondaryId);
        int targetUpdated = jdbcTemplate.update(
                "UPDATE memory_relations SET target_entity_id = ?, updated_at = ? WHERE target_entity_id = ? AND status = 'ACTIVE'",
                primaryId, now, secondaryId);
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

    private static String serializeProps(Map<String, Object> props) {
        try {
            return OBJECT_MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并派生来源 ID 集：保留 primary 已有的 sources，追加本次合并的 primary + secondary ids。
     * 去重后保持插入顺序，便于人工审查。
     */
    private static List<String> mergeDerivationSources(
            @jakarta.annotation.Nullable List<String> existing, String primaryId, String secondaryId) {
        var combined = new LinkedHashSet<String>();
        if (existing != null) {
            combined.addAll(existing);
        }
        combined.add(primaryId);
        combined.add(secondaryId);
        return List.copyOf(combined);
    }

    /** 将派生来源列表序列化为 JSON 数组字符串，空集合返回 null 保持列稀疏。 */
    private static String serializeDerivationSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
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
