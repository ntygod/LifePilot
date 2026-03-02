package com.lifepilot.memory.semantic;

import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final JdbcTemplate jdbcTemplate;
    private final ConflictDetector conflictDetector;
    private final VersionMerger versionMerger;
    private final VectorSearcher vectorSearcher;

    public SemanticMemory(JdbcTemplate jdbcTemplate,
                          ConflictDetector conflictDetector,
                          VersionMerger versionMerger,
                          VectorSearcher vectorSearcher) {
        this.jdbcTemplate = jdbcTemplate;
        this.conflictDetector = conflictDetector;
        this.versionMerger = versionMerger;
        this.vectorSearcher = vectorSearcher;
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
        var existing = conflictDetector.detectConflict(incoming);

        if (existing.isPresent()) {
            // 冲突：版本化合并
            var mergeResult = versionMerger.merge(existing.get(), incoming, conversationId);
            if (!mergeResult.isNewVersion()) {
                log.debug("语义记忆: upsert 无变化, name={}", incoming.name());
                return existing.get();
            }
            // 关闭旧版本
            var now = Instant.now();
            jdbcTemplate.update(
                    "UPDATE temporal_entities SET is_current = 0, valid_to = ?, updated_at = ? WHERE id = ?",
                    now.toString(), now.toString(), existing.get().id());

            // 插入新版本（新 ID）
            var merged = mergeResult.mergedEntity();
            var newId = UUID.randomUUID().toString();
            var entity = new TemporalEntity(
                    newId, merged.type(), merged.name(), merged.description(),
                    merged.properties(), merged.version(), true,
                    merged.validFrom(), null, conversationId,
                    merged.extractionConfidence(), merged.importanceScore(),
                    merged.accessCount(), merged.lastAccessedAt(),
                    existing.get().createdAt(), now);
            insertEntity(entity);
            updateVector(entity);
            log.debug("语义记忆: 版本化更新, name={}, version={}", entity.name(), entity.version());
            return entity;
        } else {
            // 新建
            var now = Instant.now();
            var newId = incoming.id() != null ? incoming.id() : UUID.randomUUID().toString();
            var entity = new TemporalEntity(
                    newId, incoming.type(), incoming.name(), incoming.description(),
                    incoming.properties(), 1, true,
                    now, null, conversationId,
                    incoming.extractionConfidence(), incoming.importanceScore(),
                    0, null, now, now);
            insertEntity(entity);
            updateVector(entity);
            log.debug("语义记忆: 新建实体, name={}, id={}", entity.name(), entity.id());
            return entity;
        }
    }

    /** 时间旅行查询：返回指定时间点有效的所有实体。 */
    public List<TemporalEntity> queryAtTime(Instant point) {
        return jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)",
                (rs, rowNum) -> mapRowToEntity(rs),
                point.toString(), point.toString());
    }

    /** 变更历史：返回指定 name+type 的所有版本，按 version 升序。 */
    public List<TemporalEntity> getChangeHistory(String name, EntityType type) {
        return jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE name = ? AND type = ? ORDER BY version ASC",
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
                SELECT DISTINCT te.* FROM temporal_entities te
                JOIN related r ON te.id = r.id
                WHERE te.is_current = 1 AND te.id != ?
                """,
                (rs, rowNum) -> mapRowToEntity(rs),
                entityId, entityId, maxDepth, entityId);
    }

    /** 查找当前版本实体。 */
    public Optional<TemporalEntity> findCurrentByNameAndType(String name, EntityType type) {
        var results = jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE name = ? AND type = ? AND is_current = 1",
                (rs, rowNum) -> mapRowToEntity(rs),
                name, type.name());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 归档：事务内设置 is_current=0, valid_to=now，同时归档所有当前有效关系。 */
    @Transactional
    public void archive(TemporalEntity entity) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE temporal_entities SET is_current = 0, valid_to = ?, updated_at = ? WHERE id = ?",
                now, now, entity.id());
        jdbcTemplate.update(
                "UPDATE temporal_relations SET valid_to = ? WHERE (source_entity_id = ? OR target_entity_id = ?) AND valid_to IS NULL",
                now, entity.id(), entity.id());
        log.debug("语义记忆: 归档实体, id={}, name={}", entity.id(), entity.name());
    }

    /** 增加访问计数。 */
    public void incrementAccessCount(String entityId) {
        var now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE temporal_entities SET access_count = access_count + 1, last_accessed_at = ?, updated_at = ? WHERE id = ?",
                now, now, entityId);
    }

    /** 添加关系。 */
    public void addRelation(TemporalRelation relation) {
        jdbcTemplate.update(
                "INSERT INTO temporal_relations(id, source_entity_id, target_entity_id, relation_type, strength, properties_json, valid_from, valid_to, source_conversation_id, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                relation.id(), relation.sourceEntityId(), relation.targetEntityId(),
                relation.relationType(), relation.strength(), relation.propertiesJson(),
                relation.validFrom().toString(), relation.validTo() != null ? relation.validTo().toString() : null,
                relation.sourceConversationId(), relation.createdAt().toString());
        log.debug("语义记忆: 添加关系, id={}, type={}", relation.id(), relation.relationType());
    }

    /** 查找所有当前实体，按 importance_score ASC, access_count ASC。 */
    public List<TemporalEntity> findAllCurrent() {
        return jdbcTemplate.query(
                "SELECT * FROM temporal_entities WHERE is_current = 1 ORDER BY importance_score ASC, access_count ASC",
                (rs, rowNum) -> mapRowToEntity(rs));
    }

    /**
     * 按 ID 批量查找当前/历史实体。
     *
     * @param ids 实体 ID 集合
     * @return id → TemporalEntity 映射
     */
    public Map<String, TemporalEntity> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(_ -> "?")
                .collect(Collectors.joining(","));
        String sql = "SELECT * FROM temporal_entities WHERE id IN (" + placeholders + ")";
        Object[] params = ids.toArray();
        List<TemporalEntity> list = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapRowToEntity(rs),
                params);
        return list.stream().collect(Collectors.toMap(TemporalEntity::id, e -> e));
    }

    // --- 内部方法 ---

    /** 插入实体到数据库。 */
    private void insertEntity(TemporalEntity entity) {
        String propsJson = null;
        if (!entity.properties().isEmpty()) {
            try {
                propsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(entity.properties());
            } catch (Exception e) {
                log.warn("语义记忆: properties 序列化失败, id={}", entity.id());
            }
        }
        jdbcTemplate.update(
                "INSERT INTO temporal_entities(id, type, name, description, properties_json, version, is_current, valid_from, valid_to, source_conversation_id, extraction_confidence, importance_score, access_count, last_accessed_at, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                entity.id(), entity.type().name(), entity.name(), entity.description(),
                propsJson, entity.version(), entity.isCurrent() ? 1 : 0,
                entity.validFrom().toString(),
                entity.validTo() != null ? entity.validTo().toString() : null,
                entity.sourceConversationId(),
                entity.extractionConfidence(), entity.importanceScore(),
                entity.accessCount(),
                entity.lastAccessedAt() != null ? entity.lastAccessedAt().toString() : null,
                entity.createdAt().toString(), entity.updatedAt().toString());
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
                properties = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(propsJson, Map.class);
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
}
