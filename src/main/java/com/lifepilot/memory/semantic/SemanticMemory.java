package com.lifepilot.memory.semantic;

import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemoryWriteContext;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 语义记忆服务 — 管理时序知识图谱的完整生命周期。
 *
 * <p>提供版本感知的 CRUD、冲突检测、时间旅行查询和图遍历能力。
 * 使用 JdbcTemplate 执行数据库操作。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SemanticMemory {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemory.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String DEFAULT_PERSONAL_SPACE_ID = "memory-space-personal-default";
    private static final String DEFAULT_EXPERIENCE_SPACE_ID = "memory-space-experience-default";

    private final JdbcTemplate jdbcTemplate;
    private final ConflictDetector conflictDetector;
    private final VersionMerger versionMerger;
    private final VectorSearcher vectorSearcher;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;

    /** 记忆写入回调 — 通知检索引擎数据已变更（重置 knownEmpty 短路标记）。 */
    @Nullable
    private Runnable writeCallback;

    public SemanticMemory(JdbcTemplate jdbcTemplate,
                          ConflictDetector conflictDetector,
                          VersionMerger versionMerger,
                          VectorSearcher vectorSearcher) {
        this(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher, null);
    }

    public SemanticMemory(JdbcTemplate jdbcTemplate,
                          ConflictDetector conflictDetector,
                          VersionMerger versionMerger,
                          VectorSearcher vectorSearcher,
                          @Nullable MemorySpaceRepository memorySpaceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.conflictDetector = conflictDetector;
        this.versionMerger = versionMerger;
        this.vectorSearcher = vectorSearcher;
        this.memorySpaceRepository = memorySpaceRepository;
    }

    /**
     * 设置记忆写入回调，用于在实体写入后通知检索引擎重置空数据标记。
     *
     * <p>通过 setter 注入避免 SemanticMemory ↔ HybridRetriever 循环依赖。</p>
     *
     * @param writeCallback 写入回调
     */
    public void setWriteCallback(@Nullable Runnable writeCallback) {
        this.writeCallback = writeCallback;
    }

    /**
     * 版本感知 upsert：冲突检测 → 合并/新建 → 插入 → 更新向量索引。
     *
     * @param incoming       新实体信息
     * @param conversationId 来源会话 ID
     * @return 持久化后的实体
     */
    @Transactional
    public TemporalEntity upsertWithConflictDetection(TemporalEntity incoming, String conversationId) {
        return upsertWithConflictDetection(incoming, conversationId, null);
    }

    /**
     * 带写入上下文的版本感知 upsert。
     *
     * @param incoming        新实体信息
     * @param sourceReference 来源引用（兼容旧的 conversationId/documentId/sourceId 语义）
     * @param writeContext    目标写入上下文
     * @return 持久化后的实体
     */
    @Transactional
    public TemporalEntity upsertWithConflictDetection(TemporalEntity incoming,
                                                      @Nullable String sourceReference,
                                                      @Nullable MemoryWriteContext writeContext) {
        MemoryWriteContext resolvedContext = resolveWriteContext(incoming, sourceReference, writeContext);
        var existing = conflictDetector.detectConflict(incoming, resolvedContext.spaceId());

        if (existing.isPresent()) {
            // 冲突：版本化合并
            var mergeResult = versionMerger.merge(existing.get(), incoming, sourceReference);
            if (!mergeResult.isNewVersion()) {
                log.debug("语义记忆: upsert 无变化, name={}", incoming.name());
                return existing.get();
            }
            // 关闭旧版本
            var now = Instant.now();
            closeCurrentEntityVersion(existing.get().id(), now);

            var merged = mergeResult.mergedEntity();
            var entity = new TemporalEntity(
                    existing.get().id(), merged.type(), merged.name(), merged.description(),
                    merged.properties(), merged.version(), true,
                    merged.validFrom(), null, resolvedContext.sourceConversationId(),
                    merged.extractionConfidence(), merged.importanceScore(),
                    merged.accessCount(), merged.lastAccessedAt(),
                    existing.get().createdAt(), now);
            insertEntityVersion(entity, resolvedContext, now);
            updateEntityRoot(entity, resolvedContext, now);
            updateVector(entity);
            notifyWriteCallback();
            log.debug("语义记忆: 版本化更新, name={}, version={}", entity.name(), entity.version());
            return entity;
        } else {
            // 新建
            var now = Instant.now();
            var newId = incoming.id() != null ? incoming.id() : UUID.randomUUID().toString();
            var entity = new TemporalEntity(
                    newId, incoming.type(), incoming.name(), incoming.description(),
                    incoming.properties(), 1, true,
                    now, null, resolvedContext.sourceConversationId(),
                    incoming.extractionConfidence(), incoming.importanceScore(),
                    0, null, now, now);
            insertEntityRoot(entity, resolvedContext, now);
            insertEntityVersion(entity, resolvedContext, now);
            updateVector(entity);
            notifyWriteCallback();
            log.debug("语义记忆: 新建实体, name={}, id={}", entity.name(), entity.id());
            return entity;
        }
    }

    /** 时间旅行查询：返回指定时间点有效的所有实体。 */
    public List<TemporalEntity> queryAtTime(Instant point) {
        return jdbcTemplate.query(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
                (rs, rowNum) -> mapRowToEntity(rs),
                point.toString(), point.toString());
    }

    /** 变更历史：返回指定 name+type 的所有版本，按 version 升序。 */
    public List<TemporalEntity> getChangeHistory(String name, EntityType type) {
        return jdbcTemplate.query(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE name = ? AND type = ? ORDER BY version ASC",
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name());
    }

    /** 图遍历：递归 CTE 沿当前有效关系边遍历最多 maxDepth 跳。 */
    public List<TemporalEntity> findRelated(String entityId, int maxDepth) {
        return jdbcTemplate.query(
                """
                WITH RECURSIVE related(id, depth) AS (
                    SELECT target_entity_id, 1 FROM temporal_relations
                    WHERE source_entity_id = ? AND valid_to IS NULL
                    UNION
                    SELECT target_entity_id, 1 FROM temporal_relations
                    WHERE target_entity_id = ? AND valid_to IS NULL
                    UNION
                    SELECT CASE
                        WHEN tr.source_entity_id = r.id THEN tr.target_entity_id
                        ELSE tr.source_entity_id
                    END, r.depth + 1
                    FROM temporal_relations tr
                    JOIN related r ON (tr.source_entity_id = r.id OR tr.target_entity_id = r.id)
                    WHERE tr.valid_to IS NULL AND r.depth < ?
                )
                SELECT DISTINCT te.id, te.type, te.name, te.description, te.properties_json, te.version, te.is_current, te.valid_from, te.valid_to, te.source_conversation_id, te.extraction_confidence, te.importance_score, te.access_count, te.last_accessed_at, te.created_at, te.updated_at FROM temporal_entities te
                JOIN related r ON te.id = r.id
                WHERE te.is_current = 1 AND te.id != ?
                """,
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId, entityId, maxDepth, entityId);
    }

    /** 查找当前版本实体。 */
    public Optional<TemporalEntity> findCurrentByNameAndType(String name, EntityType type) {
        return findCurrentByNameAndType(name, type, null);
    }

    /**
     * 查找当前版本实体，并应用读取过滤。
     *
     * @param name   实体名称
     * @param type   实体类型
     * @param filter 读取过滤条件
     * @return 当前版本实体
     */
    public Optional<TemporalEntity> findCurrentByNameAndType(String name,
                                                             EntityType type,
                                                             @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE name = ? AND type = ? AND is_current = 1");
        List<Object> params = new ArrayList<>();
        params.add(name);
        params.add(type.name());
        appendEntityReadFilter(sql, params, filter);
        var results = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 查询指定类型的所有当前有效实体。
     *
     * @param type 实体类型
     * @return 匹配的实体列表
     */
    public List<TemporalEntity> findCurrentByType(EntityType type) {
        return findCurrentByType(type, null);
    }

    /**
     * 查询指定类型的所有当前有效实体，并应用读取过滤。
     *
     * @param type   实体类型
     * @param filter 读取过滤条件
     * @return 匹配的实体列表
     */
    public List<TemporalEntity> findCurrentByType(EntityType type, @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE type = ? AND is_current = 1");
        List<Object> params = new ArrayList<>();
        params.add(type.name());
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY importance_score DESC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
    }

    /**
     * 查询符合过滤条件的所有当前实体 ID 集合 — 用于向量检索 pre-filter。
     *
     * @param filter 读取过滤条件
     * @return 符合条件的实体 ID 集合
     */
    public Set<String> findEligibleEntityIds(MemoryReadFilter filter) {
        var sql = new StringBuilder("SELECT id FROM memory_entities WHERE status = 'ACTIVE'");
        var params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        var ids = new LinkedHashSet<>(
                jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray()));
        // 结果集过大时返回 null，由调用方回退为后过滤
        if (ids.size() > 1000) {
            log.debug("语义记忆: pre-filter 候选集过大({}), 回退为后过滤", ids.size());
            return null;
        }
        return ids;
    }

    /**
     * 归档：事务内设置 is_current=0, valid_to=now，同时归档所有当前有效关系；
     * 向量清理走 afterCommit 钩子在事务外执行，避免事务回滚后留下"向量已删、主库未改"的不一致。
     *
     * <p>跨库（vectors.db 与主库分离）写入不能加入本地事务，因此注册
     * {@link TransactionSynchronization#afterCommit()}：主库事务真正提交后再删向量，
     * 回滚路径下向量保持原状。钩子里的失败仅告警，由后续 archive 重试自然清理。</p>
     */
    @Transactional
    public void archive(TemporalEntity entity) {
        var now = Instant.now();
        closeCurrentEntityVersion(entity.id(), now);
        jdbcTemplate.update(
                "UPDATE memory_entities SET status = 'ARCHIVED', last_seen_at = ?, updated_at = ? WHERE id = ?",
                now.toString(), now.toString(), entity.id());
        jdbcTemplate.update(
                """
                UPDATE memory_relation_versions
                SET is_current = 0,
                    valid_to = COALESCE(valid_to, ?),
                    updated_at = ?
                WHERE relation_id IN (
                    SELECT id FROM memory_relations
                    WHERE (source_entity_id = ? OR target_entity_id = ?)
                      AND status = 'ACTIVE'
                ) AND is_current = 1
                """,
                now.toString(), now.toString(), entity.id(), entity.id());
        jdbcTemplate.update(
                "UPDATE memory_relations SET status = 'ARCHIVED', updated_at = ? WHERE (source_entity_id = ? OR target_entity_id = ?) AND status = 'ACTIVE'",
                now.toString(), entity.id(), entity.id());

        registerAfterCommitVectorCleanup(entity.id());

        log.debug("语义记忆: 归档实体, id={}, name={}", entity.id(), entity.name());
    }

    /**
     * 注册事务提交后删除向量的钩子；无活跃事务（如单测直接调 archive）时立即删除。
     */
    private void registerAfterCommitVectorCleanup(String entityId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeDeleteEntityVector(entityId);
                }
            });
        } else {
            safeDeleteEntityVector(entityId);
        }
    }

    private void safeDeleteEntityVector(String entityId) {
        try {
            vectorSearcher.deleteEntityVector(entityId);
        } catch (Exception e) {
            log.warn("语义记忆: 归档后清理向量失败, id={}, error={}", entityId, e.getMessage());
        }
    }

    /** 增加访问计数。 */
    public void incrementAccessCount(String entityId) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE memory_entities SET access_count = access_count + 1, last_accessed_at = ?, updated_at = ? WHERE id = ?",
                now, now, entityId);
    }

    /**
     * 更新实体的 importanceScore。
     *
     * @param entityId 实体 ID
     * @param newScore 新的 importanceScore（已裁剪到 [0.0, 1.0]）
     */
    public void updateImportanceScore(String entityId, float newScore) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE memory_entity_versions SET importance_score = ?, updated_at = ? WHERE entity_id = ? AND is_current = 1",
                newScore, now, entityId);
    }

    /**
     * 直接更新实体描述 — 用于手动编辑场景，绕过 VersionMerger。
     *
     * @param entityId    实体 ID
     * @param description 新描述
     */
    public void updateDescription(String entityId, String description) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE memory_entity_versions SET description = ?, updated_at = ? WHERE entity_id = ? AND is_current = 1",
                description, now, entityId);
        updateVector(findById(entityId).orElse(null));
        notifyWriteCallback();
    }

    /** 添加关系。 */
    public void addRelation(TemporalRelation relation) {
        addRelation(relation, null);
    }

    /** 添加关系并记录写入上下文。 */
    public void addRelation(TemporalRelation relation, @Nullable MemoryWriteContext writeContext) {
        var now = Instant.now();
        MemoryWriteContext resolvedContext = resolveRelationWriteContext(relation, writeContext);
        String spaceId = resolvedContext.spaceId() != null
                ? resolvedContext.spaceId()
                : resolveRelationSpaceId(relation.sourceEntityId(), relation.targetEntityId());
        jdbcTemplate.update(
                """
                INSERT INTO memory_relations(
                    id, space_id, source_entity_id, target_entity_id, relation_type,
                    reality_type, status, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """,
                relation.id(),
                spaceId,
                relation.sourceEntityId(),
                relation.targetEntityId(),
                relation.relationType(),
                resolvedContext.realityType().name(),
                relation.validTo() == null ? "ACTIVE" : "ARCHIVED",
                relation.createdAt().toString(),
                now.toString());
        jdbcTemplate.update(
                """
                INSERT INTO memory_relation_versions(
                    id, relation_id, version_no, strength, properties_json,
                    is_current, valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                relation.id(),
                relation.id(),
                1,
                relation.strength(),
                relation.propertiesJson(),
                relation.validTo() == null ? 1 : 0,
                relation.validFrom().toString(),
                relation.validTo() != null ? relation.validTo().toString() : null,
                relation.createdAt().toString(),
                now.toString());
        insertRelationProvenance(relation, resolvedContext, now);
        log.debug("语义记忆: 添加关系, id={}, type={}", relation.id(), relation.relationType());
    }

    /** 查找所有当前实体，按 importance_score ASC, access_count ASC。 */
    public List<TemporalEntity> findAllCurrent() {
        return findAllCurrent(null);
    }

    /**
     * 查找所有当前实体，并应用读取过滤。
     *
     * @param filter 读取过滤条件
     * @return 实体列表
     */
    public List<TemporalEntity> findAllCurrent(@Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" ORDER BY importance_score ASC, access_count ASC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
    }

    /**
     * 按 ID 批量查找当前/历史实体。
     *
     * @param ids 实体 ID 集合
     * @return id → TemporalEntity 映射
     */
    public Map<String, TemporalEntity> findByIds(Collection<String> ids) {
        return findByIds(ids, null);
    }

    /**
     * 按 ID 批量查找当前/历史实体，并应用读取过滤。
     *
     * @param ids    实体 ID 集合
     * @param filter 读取过滤条件
     * @return id → TemporalEntity 映射
     */
    public Map<String, TemporalEntity> findByIds(Collection<String> ids, @Nullable MemoryReadFilter filter) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" AND id IN (")
                .append(buildPlaceholders(ids.size()))
                .append(")");
        params.addAll(ids);
        List<TemporalEntity> list = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapRowToEntity(rs),
                params.toArray());
        return list.stream().collect(Collectors.toMap(TemporalEntity::id, e -> e));
    }

    /**
     * 按实体类型分组统计当前有效实体数量 — 轻量 SQL 聚合，避免全量加载实体对象。
     *
     * @param filter 读取过滤条件（可为 null）
     * @return 实体类型 → 数量映射
     */
    public Map<EntityType, Integer> countByEntityType(@Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT type, COUNT(*) AS cnt FROM temporal_entities WHERE is_current = 1");
        List<Object> params = new ArrayList<>();
        appendEntityReadFilter(sql, params, filter);
        sql.append(" GROUP BY type");
        var rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        Map<EntityType, Integer> result = new java.util.EnumMap<>(EntityType.class);
        for (var row : rows) {
            try {
                EntityType type = EntityType.valueOf((String) row.get("type"));
                int count = ((Number) row.get("cnt")).intValue();
                result.put(type, count);
            } catch (IllegalArgumentException e) {
                // 未知的实体类型，跳过
                log.debug("语义记忆: 忽略未知实体类型, type={}", row.get("type"));
            }
        }
        return result;
    }

    /**
     * 统计当前有效实体总数。
     *
     * @return 当前有效实体数量
     */
    public long countCurrent() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM temporal_entities WHERE is_current = 1",
                Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 统计当前有效关系总数。
     *
     * @return 当前有效关系数量
     */
    public long countCurrentRelations() {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM temporal_relations WHERE valid_to IS NULL",
                Long.class);
        return count != null ? count : 0L;
    }

    /** 按类型统计当前实体数量 — 轻量 SQL 聚合，返回 type name → 数量映射。 */
    public Map<String, Long> countCurrentByType() {
        return jdbcTemplate.query(
                "SELECT type, COUNT(*) AS cnt FROM temporal_entities WHERE is_current = 1 GROUP BY type",
                rs -> {
                    Map<String, Long> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("type"), rs.getLong("cnt"));
                    }
                    return result;
                });
    }

    /** 统计最近 N 天内被访问的当前实体数量。 */
    public long countRecentlyAccessed(int days) {
        String cutoff = Instant.now().minus(java.time.Duration.ofDays(days)).toString();
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM temporal_entities WHERE is_current = 1 AND last_accessed_at > ?",
                Long.class, cutoff);
        return count != null ? count : 0L;
    }

    /** 当前实体的平均重要度分数。 */
    public float averageImportanceScore() {
        var avg = jdbcTemplate.queryForObject(
                "SELECT AVG(importance_score) FROM temporal_entities WHERE is_current = 1",
                Double.class);
        return avg != null ? avg.floatValue() : 0f;
    }

    /**
     * 查询所有当前有效关系。
     *
     * @return 当前有效关系列表
     */
    public List<TemporalRelation> findAllCurrentRelations() {
        return jdbcTemplate.query(
                "SELECT id, source_entity_id, target_entity_id, relation_type, strength, properties_json, valid_from, valid_to, source_conversation_id, created_at FROM temporal_relations WHERE valid_to IS NULL ORDER BY created_at DESC",
                (rs, rowNum) -> mapRowToRelation(rs));
    }

    /**
     * 查询与指定实体相关的所有当前有效关系（作为 source 或 target）。
     *
     * @param entityId 实体 ID
     * @return 相关关系列表
     */
    public List<TemporalRelation> findRelationsByEntityId(String entityId) {
        return jdbcTemplate.query(
                "SELECT id, source_entity_id, target_entity_id, relation_type, strength, properties_json, valid_from, valid_to, source_conversation_id, created_at FROM temporal_relations WHERE (source_entity_id = ? OR target_entity_id = ?) AND valid_to IS NULL ORDER BY created_at DESC",
                (rs, rowNum) -> mapRowToRelation(rs),
                entityId, entityId);
    }

    /**
     * 按 ID 查找单个实体。
     *
     * @param entityId 实体 ID
     * @return 实体（如存在）
     */
    public Optional<TemporalEntity> findById(String entityId) {
        var results = jdbcTemplate.query(
                "SELECT id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at FROM temporal_entities WHERE id = ? ORDER BY is_current DESC, version DESC",
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    // --- 内部方法 ---

    /** 通知检索引擎数据已变更，重置 knownEmpty 短路标记。 */
    private void notifyWriteCallback() {
        if (writeCallback != null) {
            try {
                writeCallback.run();
            } catch (Exception e) {
                log.warn("语义记忆: writeCallback 执行失败, error={}", e.getMessage());
            }
        }
    }

    /** 插入或更新实体根记录 — 使用 upsert 语义防止 PK 冲突。 */
    private void insertEntityRoot(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        String spaceId = writeContext.spaceId() != null
                ? writeContext.spaceId()
                : resolveDefaultSpaceId(entity.type());
        String memoryScope = (writeContext.memoryScope() != null
                ? writeContext.memoryScope()
                : resolveDefaultScope(entity.type())).name();
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, last_accessed_at,
                    first_seen_at, last_seen_at, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    canonical_name = excluded.canonical_name,
                    normalized_name = excluded.normalized_name,
                    status = excluded.status,
                    access_count = excluded.access_count,
                    last_accessed_at = excluded.last_accessed_at,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at
                """,
                entity.id(),
                spaceId,
                memoryScope,
                entity.type().name(),
                entity.name(),
                normalizeName(entity.name()),
                MemoryRealityType.UNKNOWN.name(),
                entity.validTo() == null ? "ACTIVE" : "ARCHIVED",
                entity.accessCount(),
                entity.lastAccessedAt() != null ? entity.lastAccessedAt().toString() : null,
                now.toString(),
                now.toString(),
                entity.createdAt().toString(),
                now.toString());
    }

    /** 插入实体版本与 provenance。 */
    private void insertEntityVersion(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        String propsJson = null;
        if (!entity.properties().isEmpty()) {
            try {
                propsJson = MAPPER.writeValueAsString(entity.properties());
            } catch (Exception e) {
                log.warn("语义记忆: properties 序列化失败, id={}", entity.id());
            }
        }
        String versionId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description, properties_json,
                    extraction_confidence, importance_score, is_current,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                versionId, entity.id(), entity.version(), entity.description(),
                propsJson, entity.extractionConfidence(), entity.importanceScore(),
                entity.isCurrent() ? 1 : 0,
                entity.validFrom().toString(),
                entity.validTo() != null ? entity.validTo().toString() : null,
                now.toString(), now.toString());
        insertEntityProvenance(entity, versionId, writeContext, now);
    }

    private void updateEntityRoot(TemporalEntity entity, MemoryWriteContext writeContext, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE memory_entities
                SET memory_scope = ?,
                    entity_type = ?,
                    canonical_name = ?,
                    normalized_name = ?,
                    status = 'ACTIVE',
                    access_count = ?,
                    last_accessed_at = ?,
                    last_seen_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (writeContext.memoryScope() != null ? writeContext.memoryScope() : resolveDefaultScope(entity.type())).name(),
                entity.type().name(),
                entity.name(),
                normalizeName(entity.name()),
                entity.accessCount(),
                entity.lastAccessedAt() != null ? entity.lastAccessedAt().toString() : null,
                now.toString(),
                now.toString(),
                entity.id());
    }

    private void insertEntityProvenance(TemporalEntity entity,
                                        String versionId,
                                        MemoryWriteContext writeContext,
                                        Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_provenances(
                    id, entity_id, version_id, origin_type, source_reference, source_conversation_id,
                    source_session_id, source_turn_id, source_entry_id, source_document_id,
                    source_knowledge_base_id, source_datastore_id, source_collection_id,
                    confidence, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                entity.id(),
                versionId,
                writeContext.originType().name(),
                writeContext.sourceReference(),
                writeContext.sourceConversationId(),
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceEntryId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId(),
                writeContext.sourceDatastoreId(),
                writeContext.sourceCollectionId(),
                entity.extractionConfidence(),
                now.toString());
    }

    private void insertRelationProvenance(TemporalRelation relation,
                                          MemoryWriteContext writeContext,
                                          Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_relation_provenances(
                    id, relation_id, version_id, origin_type, source_reference, source_conversation_id,
                    source_session_id, source_turn_id, source_document_id,
                    source_knowledge_base_id, source_datastore_id, source_collection_id,
                    confidence, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                relation.id(),
                relation.id(),
                writeContext.originType().name(),
                writeContext.sourceReference(),
                writeContext.sourceConversationId(),
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId(),
                writeContext.sourceDatastoreId(),
                writeContext.sourceCollectionId(),
                relation.strength(),
                now.toString());
    }

    private void closeCurrentEntityVersion(String entityId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE memory_entity_versions
                SET is_current = 0,
                    valid_to = COALESCE(valid_to, ?),
                    updated_at = ?
                WHERE entity_id = ? AND is_current = 1
                """,
                now.toString(),
                now.toString(),
                entityId);
    }

    private String resolveRelationSpaceId(String sourceEntityId, String targetEntityId) {
        List<String> spaceIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT space_id FROM memory_entities WHERE id IN (?, ?)",
                String.class,
                sourceEntityId,
                targetEntityId
        );
        if (spaceIds.isEmpty()) {
            return DEFAULT_PERSONAL_SPACE_ID;
        }
        if (spaceIds.size() > 1) {
            throw new IllegalArgumentException("暂不支持跨记忆空间建立关系");
        }
        return spaceIds.getFirst();
    }

    private MemoryWriteContext resolveWriteContext(TemporalEntity entity,
                                                   @Nullable String sourceReference,
                                                   @Nullable MemoryWriteContext writeContext) {
        if (writeContext == null) {
            return defaultWriteContext(entity, sourceReference);
        }
        return new MemoryWriteContext(
                writeContext.spaceId(),
                writeContext.memoryScope(),
                writeContext.originType(),
                writeContext.realityType(),
                writeContext.sourceReference() != null ? writeContext.sourceReference() : sourceReference,
                writeContext.sourceConversationId() != null ? writeContext.sourceConversationId() : sourceReference,
                writeContext.sourceSessionId(),
                writeContext.sourceTurnId(),
                writeContext.sourceEntryId(),
                writeContext.sourceDocumentId(),
                writeContext.sourceKnowledgeBaseId(),
                writeContext.sourceDatastoreId(),
                writeContext.sourceCollectionId()
        );
    }

    private MemoryWriteContext defaultWriteContext(TemporalEntity entity, @Nullable String sourceReference) {
        return new MemoryWriteContext(
                resolveDefaultSpaceId(entity.type()),
                resolveDefaultScope(entity.type()),
                detectOriginType(sourceReference),
                MemoryRealityType.UNKNOWN,
                sourceReference,
                sourceReference,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private MemoryWriteContext resolveRelationWriteContext(TemporalRelation relation,
                                                           @Nullable MemoryWriteContext writeContext) {
        if (writeContext != null) {
            return writeContext;
        }
        return new MemoryWriteContext(
                resolveRelationSpaceId(relation.sourceEntityId(), relation.targetEntityId()),
                null,
                detectOriginType(relation.sourceConversationId()),
                MemoryRealityType.UNKNOWN,
                relation.sourceConversationId(),
                relation.sourceConversationId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private MemoryScope resolveDefaultScope(EntityType type) {
        return switch (type) {
            case EXPERIENCE -> MemoryScope.AGENT_EXPERIENCE;
            case PREFERENCE, HABIT, GOAL, SKILL -> MemoryScope.USER_PROFILE;
            default -> MemoryScope.USER_FACT;
        };
    }

    private String resolveDefaultSpaceId(EntityType type) {
        if (type == EntityType.EXPERIENCE) {
            if (memorySpaceRepository != null) {
                return memorySpaceRepository.ensureDefaultExperienceSpace().id();
            }
            return DEFAULT_EXPERIENCE_SPACE_ID;
        }
        if (memorySpaceRepository != null) {
            return memorySpaceRepository.ensureDefaultPersonalSpace().id();
        }
        return DEFAULT_PERSONAL_SPACE_ID;
    }

    private MemoryOriginType detectOriginType(@Nullable String sourceReference) {
        if (sourceReference == null || sourceReference.isBlank()) {
            return MemoryOriginType.UNKNOWN;
        }
        return MemoryOriginType.UNKNOWN;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private void appendEntityReadFilter(StringBuilder sql,
                                        List<Object> params,
                                        @Nullable MemoryReadFilter filter) {
        if (filter == null || filter.isUnrestricted()) {
            return;
        }
        if (filter.restrictsSpaces()) {
            sql.append(" AND space_id IN (")
                    .append(buildPlaceholders(filter.spaceIds().size()))
                    .append(")");
            params.addAll(filter.spaceIds());
        }
        if (filter.restrictsScopes()) {
            sql.append(" AND memory_scope IN (")
                    .append(buildPlaceholders(filter.scopes().size()))
                    .append(")");
            params.addAll(filter.scopes().stream().map(Enum::name).toList());
        }
    }

    private String buildPlaceholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** 更新向量索引（事务外，失败不影响实体持久化）。 */
    private void updateVector(TemporalEntity entity) {
        try {
            vectorSearcher.upsertEntityVector(entity.id(), entity.textRepresentation());
        } catch (Exception e) {
            log.warn("语义记忆: 向量索引更新失败, entityId={}, error={}", entity.id(), e.getMessage());
        }
    }

    /** ResultSet 行映射为 TemporalEntity。 */
    @SuppressWarnings("unchecked")
    private TemporalEntity mapRowToEntity(java.sql.ResultSet rs) throws java.sql.SQLException {
        String propsJson = rs.getString("properties_json");
        Map<String, Object> properties = Map.of();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                properties = MAPPER.readValue(propsJson, Map.class);
            } catch (Exception e) {
                log.warn("语义记忆: properties_json 解析失败, id={}", rs.getString("id"));
            }
        }

        String validToStr = rs.getString("valid_to");
        String lastAccessedStr = rs.getString("last_accessed_at");

        return new TemporalEntity(
                rs.getString("id"),
                EntityType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("description"),
                properties,
                rs.getInt("version"),
                rs.getInt("is_current") == 1,
                Instant.parse(rs.getString("valid_from")),
                validToStr != null ? Instant.parse(validToStr) : null,
                rs.getString("source_conversation_id"),
                rs.getFloat("extraction_confidence"),
                rs.getFloat("importance_score"),
                rs.getInt("access_count"),
                lastAccessedStr != null ? Instant.parse(lastAccessedStr) : null,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    /** ResultSet 行映射为 TemporalRelation。 */
    private TemporalRelation mapRowToRelation(java.sql.ResultSet rs) throws java.sql.SQLException {
        String validToStr = rs.getString("valid_to");
        return new TemporalRelation(
                rs.getString("id"),
                rs.getString("source_entity_id"),
                rs.getString("target_entity_id"),
                rs.getString("relation_type"),
                rs.getFloat("strength"),
                rs.getString("properties_json"),
                Instant.parse(rs.getString("valid_from")),
                validToStr != null ? Instant.parse(validToStr) : null,
                rs.getString("source_conversation_id"),
                Instant.parse(rs.getString("created_at"))
        );
    }
}
