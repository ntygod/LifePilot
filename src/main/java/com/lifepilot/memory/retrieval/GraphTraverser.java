package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.scope.MemoryReadFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图遍历检索器 — 基于 SQLite 递归 CTE 的 N 跳关系遍历。
 *
 * <p>从查询文本中识别起始实体（名称精确匹配），使用递归 CTE 沿当前有效关系边
 * 遍历最多 2 跳，depth=1 得分高于 depth=2。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class GraphTraverser {

    private static final Logger log = LoggerFactory.getLogger(GraphTraverser.class);

    private final JdbcTemplate jdbcTemplate;
    private Boolean overlayTableAvailable;

    public GraphTraverser(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 图遍历检索。
     *
     * @param query 查询文本
     * @param topK  返回前 K 个结果
     * @return 排名条目列表
     */
    public List<RankedItem> traverse(String query, int topK) {
        return traverse(query, topK, null);
    }

    /**
     * 图遍历检索，并按读取过滤器约束起始实体选择。
     *
     * <p>过滤起始实体可以避免隔离项目中同名全局实体抢先成为 graph start，
     * 导致项目内同名实体的关系无法召回。</p>
     */
    public List<RankedItem> traverse(String query, int topK, @Nullable MemoryReadFilter filter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 从查询文本中识别起始实体（名称精确匹配）
        var startEntities = findStartEntities(query, filter);
        if (startEntities.isEmpty()) {
            log.debug("图遍历: 未识别到起始实体, query={}", query);
            return List.of();
        }

        // 取第一个匹配的起始实体
        String startEntityId = startEntities.getFirst();

        try {
            return jdbcTemplate.query(
                    """
                    WITH RECURSIVE graph(entity_id, depth) AS (
                        SELECT CASE
                            WHEN source_entity_id = ? THEN target_entity_id
                            ELSE source_entity_id
                        END, 1
                        FROM temporal_relations
                        WHERE (source_entity_id = ? OR target_entity_id = ?)
                          AND valid_to IS NULL
                        UNION
                        SELECT CASE
                            WHEN tr.source_entity_id = g.entity_id THEN tr.target_entity_id
                            ELSE tr.source_entity_id
                        END, g.depth + 1
                        FROM temporal_relations tr
                        JOIN graph g ON (tr.source_entity_id = g.entity_id OR tr.target_entity_id = g.entity_id)
                        WHERE tr.valid_to IS NULL AND g.depth < 2
                    )
                    SELECT te.id, te.type, te.name, te.description,
                           MIN(g.depth) AS min_depth,
                           te.last_accessed_at, te.importance_score, te.updated_at
                    FROM graph g
                    JOIN temporal_entities te ON te.id = g.entity_id
                    WHERE te.is_current = 1 AND te.id != ?
                      AND te.lifecycle_state NOT IN ('EXPIRED', 'SUPERSEDED', 'ARCHIVED', 'CANCELLED')
                    GROUP BY te.id
                    ORDER BY min_depth ASC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        int depth = rs.getInt("min_depth");
                        // depth=1 得分 1.0，depth=2 得分 0.5
                        float score = depth == 1 ? 1.0f : 0.5f;
                        String lastAccessedStr = rs.getString("last_accessed_at");
                        String updatedAtStr = rs.getString("updated_at");
                        return new RankedItem(
                                rs.getString("id"),
                                rs.getString("type"),
                                rs.getString("name"),
                                rs.getString("description"),
                                score,
                                lastAccessedStr != null ? Instant.parse(lastAccessedStr) : null,
                                rs.getFloat("importance_score"),
                                null,
                                updatedAtStr != null ? Instant.parse(updatedAtStr) : null);
                    },
                    startEntityId, startEntityId, startEntityId, startEntityId, topK);
        } catch (Exception e) {
            log.warn("图遍历: 查询失败, startEntityId={}, error={}", startEntityId, e.getMessage());
            return List.of();
        }
    }

    /** 从查询文本中识别起始实体（名称精确匹配 temporal_entities）。 */
    private List<String> findStartEntities(String query) {
        return findStartEntities(query, null);
    }

    private List<String> findStartEntities(String query, @Nullable MemoryReadFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM temporal_entities WHERE is_current = 1"
                        + " AND lifecycle_state NOT IN ('EXPIRED', 'SUPERSEDED', 'ARCHIVED', 'CANCELLED')"
                        + " AND ? LIKE '%' || name || '%'");
        List<Object> params = new ArrayList<>();
        params.add(query);
        appendReadFilter(sql, params, filter);
        sql.append(" ORDER BY LENGTH(name) DESC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> rs.getString("id"),
                params.toArray());
    }

    private void appendReadFilter(StringBuilder sql,
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
        appendOverlaySuppression(sql, params, filter);
    }

    private void appendOverlaySuppression(StringBuilder sql,
                                          List<Object> params,
                                          MemoryReadFilter filter) {
        if (!filter.restrictsSpaces() || !isOverlayTableAvailable()) {
            return;
        }
        sql.append(" AND NOT EXISTS (")
                .append("SELECT 1 FROM memory_entity_overlays mo ")
                .append("JOIN memory_entities overlay_root ON overlay_root.id = mo.overlay_entity_id ")
                .append("WHERE mo.base_entity_id = temporal_entities.id ")
                .append("AND overlay_root.status = 'ACTIVE' ")
                .append("AND overlay_root.space_id IN (")
                .append(buildPlaceholders(filter.spaceIds().size()))
                .append("))");
        params.addAll(filter.spaceIds());
    }

    private boolean isOverlayTableAvailable() {
        if (overlayTableAvailable != null) {
            return overlayTableAvailable;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='memory_entity_overlays'",
                    Integer.class);
            overlayTableAvailable = count != null && count > 0;
        } catch (Exception e) {
            overlayTableAvailable = false;
        }
        return overlayTableAvailable;
    }

    private String buildPlaceholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
