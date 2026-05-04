package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.EntityProvenanceDto;
import com.lifepilot.interaction.web.model.MemoryProvenanceSummaryDto;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.semantic.EntityType;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/**
 * 记忆来源数据访问仓库 — 封装 {@code memory_entity_provenances}、{@code temporal_entities} 等跨表查询。
 *
 * @author zsg
 * @since 2026-04-11
 */
@Repository
public class MemoryProvenanceRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryProvenanceRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public MemoryProvenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 实体元数据 — 包含空间、记忆范围和真实性类型信息。
     */
    public record EntityMetadata(
            String entityId,
            @Nullable String spaceId,
            @Nullable String memoryScope,
            @Nullable String realityType
    ) {}

    // ========== (A) 实体来源明细查询 ==========

    /**
     * 查询指定实体的来源明细，支持多维度过滤。
     *
     * @param entityId              实体 ID
     * @param originType            来源类型，可为空
     * @param sourceKnowledgeBaseId 知识库 ID，可为空
     * @param sourceDocumentId      文档 ID，可为空
     * @return 来源明细列表（按创建时间降序）
     */
    public List<EntityProvenanceDto> findEntityProvenances(String entityId,
                                                           @Nullable String originType,
                                                           @Nullable String sourceKnowledgeBaseId,
                                                           @Nullable String sourceDocumentId) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();
        conditions.add("entity_id = ?");
        params.add(entityId);
        appendProvenanceFilters(conditions, params, originType, sourceKnowledgeBaseId, sourceDocumentId);
        String sql = """
                SELECT origin_type, source_reference, source_conversation_id, source_session_id,
                       source_turn_id, source_entry_id, source_document_id, source_knowledge_base_id,
                       confidence, created_at
                FROM memory_entity_provenances
                WHERE %s
                ORDER BY created_at DESC
                """.formatted(String.join(" AND ", conditions));
        return jdbcTemplate.query(sql, (rs, rowNum) -> new EntityProvenanceDto(
                rs.getString("origin_type"),
                rs.getString("source_reference"),
                rs.getString("source_conversation_id"),
                rs.getString("source_session_id"),
                rs.getString("source_turn_id"),
                rs.getString("source_entry_id"),
                rs.getString("source_document_id"),
                null,
                rs.getString("source_knowledge_base_id"),
                null,
                rs.getFloat("confidence"),
                Instant.parse(rs.getString("created_at"))
        ), params.toArray());
    }

    // ========== (B) 最近来源摘要查询 ==========

    /**
     * 查询最近的来源摘要（关联实体信息），支持多维度过滤。
     *
     * @param originType            来源类型，可为空
     * @param sourceKnowledgeBaseId 知识库 ID，可为空
     * @param sourceDocumentId      文档 ID，可为空
     * @param limit                 最大返回条数
     * @return 来源摘要列表（按创建时间降序）
     */
    public List<MemoryProvenanceSummaryDto> findRecentProvenanceSummaries(@Nullable String originType,
                                                                          @Nullable String sourceKnowledgeBaseId,
                                                                          @Nullable String sourceDocumentId,
                                                                          int limit) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();
        appendProvenanceFilters(conditions, params, originType, sourceKnowledgeBaseId, sourceDocumentId);
        String whereClause = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        params.add(limit);
        String sql = """
                SELECT p.entity_id,
                       te.name AS entity_name,
                       te.type AS entity_type,
                       te.memory_scope AS entity_memory_scope,
                       te.reality_type AS entity_reality_type,
                       p.origin_type,
                       p.source_reference,
                       p.source_conversation_id,
                       p.source_session_id,
                       p.source_turn_id,
                       p.source_entry_id,
                       p.source_document_id,
                       p.source_knowledge_base_id,
                       p.confidence,
                       p.created_at
                FROM memory_entity_provenances p
                JOIN temporal_entities te ON te.id = p.entity_id AND te.is_current = 1
                %s
                ORDER BY p.created_at DESC
                LIMIT ?
                """.formatted(whereClause);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String entityType = rs.getString("entity_type");
            String entityTypeLabel = entityType;
            if (entityType != null && !entityType.isBlank()) {
                try {
                    entityTypeLabel = EntityType.valueOf(entityType).label();
                } catch (IllegalArgumentException ignored) {
                    entityTypeLabel = entityType;
                }
            }
            return new MemoryProvenanceSummaryDto(
                    rs.getString("entity_id"),
                    rs.getString("entity_name"),
                    entityType,
                    entityTypeLabel,
                    rs.getString("entity_memory_scope"),
                    rs.getString("entity_reality_type"),
                    rs.getString("origin_type"),
                    rs.getString("source_reference"),
                    rs.getString("source_conversation_id"),
                    rs.getString("source_session_id"),
                    rs.getString("source_turn_id"),
                    rs.getString("source_entry_id"),
                    rs.getString("source_document_id"),
                    null,
                    rs.getString("source_knowledge_base_id"),
                    null,
                    rs.getFloat("confidence"),
                    Instant.parse(rs.getString("created_at"))
            );
        }, params.toArray());
    }

    // ========== (C) 按来源过滤实体 ID ==========

    /**
     * 根据来源条件查询匹配的实体 ID 集合。
     *
     * <p>全部过滤条件为空时返回 {@code null}，表示不进行来源过滤。</p>
     *
     * @param originType            来源类型，可为空
     * @param sourceKnowledgeBaseId 知识库 ID，可为空
     * @param sourceDocumentId      文档 ID，可为空
     * @return 匹配的实体 ID 集合，或 {@code null}（无过滤条件时）
     */
    @Nullable
    public Set<String> findEntityIdsByProvenanceFilters(@Nullable String originType,
                                                         @Nullable String sourceKnowledgeBaseId,
                                                         @Nullable String sourceDocumentId) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();
        appendProvenanceFilters(conditions, params, originType, sourceKnowledgeBaseId, sourceDocumentId);
        if (conditions.isEmpty()) {
            return null;
        }
        String sql = """
                SELECT DISTINCT entity_id
                FROM memory_entity_provenances
                WHERE %s
                """.formatted(String.join(" AND ", conditions));
        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql, String.class, params.toArray()));
    }

    // ========== (D) 实体元数据批量加载 ==========

    /**
     * 批量加载实体元数据（空间、记忆范围、真实性类型）。
     *
     * @param entityIds 实体 ID 集合
     * @return 实体 ID → 元数据映射
     */
    public Map<String, EntityMetadata> loadEntityMetadata(Collection<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        var rows = jdbcTemplate.query(
                """
                SELECT id, space_id, memory_scope, reality_type, is_current, version
                FROM temporal_entities
                WHERE id IN (%s)
                ORDER BY id ASC, is_current DESC, version DESC
                """.formatted(placeholders),
                (rs, rowNum) -> new EntityMetadata(
                        rs.getString("id"),
                        rs.getString("space_id"),
                        rs.getString("memory_scope"),
                        rs.getString("reality_type")
                ),
                entityIds.toArray()
        );
        Map<String, EntityMetadata> metadataById = new LinkedHashMap<>();
        for (var row : rows) {
            metadataById.putIfAbsent(row.entityId(), row);
        }
        return metadataById;
    }

    // ========== (E/F/G) 名称批量查找 ==========

    /**
     * 批量加载知识库名称。
     *
     * @param ids 知识库 ID 集合
     * @return ID → 名称映射
     */
    public Map<String, String> loadKnowledgeBaseNames(Collection<String> ids) {
        return loadNames("knowledge_bases", "id", "name", ids);
    }

    /**
     * 批量加载文档文件名。
     *
     * @param ids 文档 ID 集合
     * @return ID → 文件名映射
     */
    public Map<String, String> loadDocumentNames(Collection<String> ids) {
        return loadNames("documents", "id", "file_name", ids);
    }

    // ========== (H) 生命周期闭环 — 失效标记 & 按来源回查实体 ==========

    /**
     * 将指定来源对象关联的 provenance 记录全部置为 {@code STALE} —— 源对象失效 / 删除时调用。
     *
     * <p>V15 为 {@code memory_entity_provenances} 新增了 {@code status} / {@code invalidated_at}
     * 两列，此方法将匹配来源对象的行批量置为 {@code STALE}，用于后续再验证与 L4 同步。</p>
     *
     * @param type     来源对象类型
     * @param sourceId 来源对象主键
     * @param when     失效时刻
     */
    public void markStale(SourceType type, String sourceId, Instant when) {
        String column = sourceColumn(type);
        int affected = jdbcTemplate.update(
                "UPDATE memory_entity_provenances SET status = 'STALE', invalidated_at = ? WHERE "
                        + column + " = ?",
                when.toString(), sourceId);
        log.debug("记忆溯源: markStale type={}, sourceId={}, affected={}", type, sourceId, affected);
    }

    /**
     * 查找所有由指定来源对象贡献过 provenance 的实体 ID（去重）。
     *
     * @param type     来源对象类型
     * @param sourceId 来源对象主键
     * @return 实体 ID 列表（可能为空）
     */
    public List<String> findEntityIdsBySource(SourceType type, String sourceId) {
        String column = sourceColumn(type);
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT entity_id FROM memory_entity_provenances WHERE " + column + " = ?",
                String.class, sourceId);
    }

    /**
     * 批量查询存在 {@code STALE} 状态 provenance 的实体 ID 子集 —— 供检索层给结果
     * 打 {@code needsRevalidation=true} 标注。
     *
     * <p>一次 SQL IN 查询，避免每条检索结果单独走 {@code findEntityProvenances} 的 N+1。
     * 仅返回入参集合中"至少有一条 provenance 处于 STALE"的实体 ID；若入参为空则直接返回空集。</p>
     *
     * @param entityIds 待检查的实体 ID 集合（通常是单次检索的 topK 结果）
     * @return 需要复核的实体 ID 集合（去重），不含未命中的 ID
     */
    public Set<String> findStaleEntityIds(Collection<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Set.of();
        }
        List<String> uniqueIds = entityIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (uniqueIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                SELECT DISTINCT entity_id
                FROM memory_entity_provenances
                WHERE status = 'STALE'
                  AND entity_id IN (%s)
                """.formatted(placeholders);
        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql, String.class, uniqueIds.toArray()));
    }

    /**
     * 列出 memory_entity_provenances 中当前仍 {@code VALID} 状态行引用过的所有
     * document ID（去重），供 Task 27 {@code OrphanProvenanceScanner} 与
     * {@code session_documents} 对照检测孤儿引用。
     *
     * <p>只扫 {@code status = 'VALID'}：已由 {@code ProvenanceStaleListener} 标为
     * STALE 的行不再重复发 {@link com.lifepilot.memory.lifecycle.events.SourceInvalidated}
     * 事件，避免幂等事件污染下游。</p>
     *
     * @return 去重后的 document ID 列表（非 null）
     */
    public List<String> listDistinctSourceDocumentIds() {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT source_document_id
                FROM memory_entity_provenances
                WHERE source_document_id IS NOT NULL
                  AND status = 'VALID'
                """,
                String.class);
    }

    private String sourceColumn(SourceType type) {
        return switch (type) {
            case DOCUMENT -> "source_document_id";
            case KNOWLEDGE_BASE -> "source_knowledge_base_id";
            case SESSION -> "source_conversation_id";
        };
    }

    // ========== 内部辅助 ==========

    /**
     * 追加来源过滤条件到 SQL WHERE 子句。
     */
    private void appendProvenanceFilters(List<String> conditions,
                                          List<Object> params,
                                          @Nullable String originType,
                                          @Nullable String sourceKnowledgeBaseId,
                                          @Nullable String sourceDocumentId) {
        if (originType != null && !originType.isBlank()) {
            conditions.add("origin_type = ?");
            params.add(originType.trim());
        }
        if (sourceKnowledgeBaseId != null && !sourceKnowledgeBaseId.isBlank()) {
            conditions.add("source_knowledge_base_id = ?");
            params.add(sourceKnowledgeBaseId.trim());
        }
        if (sourceDocumentId != null && !sourceDocumentId.isBlank()) {
            conditions.add("source_document_id = ?");
            params.add(sourceDocumentId.trim());
        }
    }

    /**
     * 通用名称批量查找 — 从指定表按 ID 列表查询名称。
     */
    private Map<String, String> loadNames(String tableName,
                                           String idColumn,
                                           String nameColumn,
                                           Collection<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = rawIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT %s AS item_id, %s AS item_name
                FROM %s
                WHERE %s IN (%s)
                """.formatted(idColumn, nameColumn, tableName, idColumn, placeholders);
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String itemId = rs.getString("item_id");
            String itemName = rs.getString("item_name");
            if (itemId != null && !itemId.isBlank() && itemName != null && !itemName.isBlank()) {
                names.put(itemId, itemName);
            }
        }, ids.toArray());
        return names;
    }
}
