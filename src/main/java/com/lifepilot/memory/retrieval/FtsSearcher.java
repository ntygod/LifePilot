package com.lifepilot.memory.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FTS5 全文搜索器 — 基于 SQLite FTS5 + BM25 的消息和实体关键词检索。
 *
 * <p>通过 session_transcript_entries_fts 全文索引检索匹配 transcript 消息，
 * 再通过 source_conversation_id 关联到 temporal_entities，返回相关实体的排名列表。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FtsSearcher {

    private static final Logger log = LoggerFactory.getLogger(FtsSearcher.class);

    private final JdbcTemplate jdbcTemplate;

    public FtsSearcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * FTS5 全文搜索 — 双路合并：会话记录 FTS + 实体文本子串匹配。
     *
     * @param query 查询文本
     * @param topK  返回前 K 个结果
     * @return 排名条目列表
     */
    public List<RankedItem> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            // 路径 1: 通过会话记录 FTS5 检索关联实体
            String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(query);
            List<RankedItem> transcriptResults = normalizedQuery.isBlank()
                    ? List.of()
                    : searchViaTranscript(normalizedQuery, topK);

            // 路径 2: 实体名称/描述子串匹配（补充无 source_conversation_id 的实体）
            List<RankedItem> entityResults = searchEntityText(query, topK);

            // 合并去重（同一实体保留高分项）
            var merged = new LinkedHashMap<String, RankedItem>();
            for (var item : transcriptResults) {
                merged.put(item.entityId(), item);
            }
            for (var item : entityResults) {
                merged.merge(item.entityId(), item,
                        (existing, incoming) -> existing.score() >= incoming.score() ? existing : incoming);
            }

            return merged.values().stream()
                    .sorted(Comparator.comparingDouble(RankedItem::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("全文搜索: 查询失败, query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 通过会话记录 FTS5 检索关联实体。 */
    private List<RankedItem> searchViaTranscript(String normalizedQuery, int topK) {
        return jdbcTemplate.query(
                """
                WITH matched_sessions AS (
                    SELECT e.session_id AS session_id,
                           MAX(-session_transcript_entries_fts.rank) AS score
                    FROM session_transcript_entries_fts
                    JOIN session_transcript_entries e ON session_transcript_entries_fts.rowid = e.rowid
                    WHERE session_transcript_entries_fts MATCH ?
                      AND e.entry_type IN ('user_message', 'assistant_message')
                      AND e.visible_to_user = 1
                    GROUP BY e.session_id
                )
                SELECT te.id, te.type, te.name, te.description,
                       matched_sessions.score AS score,
                       te.last_accessed_at, te.importance_score, te.valid_to,
                       te.updated_at
                FROM matched_sessions
                JOIN temporal_entities te ON te.source_conversation_id = matched_sessions.session_id
                WHERE te.is_current = 1
                  AND (te.valid_to IS NULL OR te.valid_to > datetime('now'))
                  AND te.lifecycle_state NOT IN ('EXPIRED', 'SUPERSEDED', 'ARCHIVED', 'CANCELLED')
                ORDER BY matched_sessions.score DESC, te.importance_score DESC, te.updated_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapRankedItem(rs),
                normalizedQuery, topK);
    }

    /** 通过实体名称/描述子串匹配检索（补充 FTS 无法覆盖的无 source_conversation_id 实体）。 */
    private List<RankedItem> searchEntityText(String query, int topK) {
        try {
            String likePattern = "%" + query.trim() + "%";
            return jdbcTemplate.query(
                    """
                    SELECT te.id, te.type, te.name, te.description,
                           0.5 AS score,
                           te.last_accessed_at, te.importance_score, te.valid_to,
                           te.updated_at
                    FROM temporal_entities te
                    WHERE te.is_current = 1
                      AND (te.valid_to IS NULL OR te.valid_to > datetime('now'))
                      AND te.lifecycle_state NOT IN ('EXPIRED', 'SUPERSEDED', 'ARCHIVED', 'CANCELLED')
                      AND (te.name LIKE ? OR COALESCE(te.description, '') LIKE ?)
                    ORDER BY te.importance_score DESC, te.updated_at DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> mapRankedItem(rs),
                    likePattern, likePattern, topK);
        } catch (Exception e) {
            log.warn("全文搜索: 实体文本搜索失败, query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 行映射为 RankedItem。 */
    private RankedItem mapRankedItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        String lastAccessedStr = rs.getString("last_accessed_at");
        String validToStr = rs.getString("valid_to");
        String updatedAtStr = rs.getString("updated_at");
        return new RankedItem(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getFloat("score"),
                lastAccessedStr != null ? Instant.parse(lastAccessedStr) : null,
                rs.getFloat("importance_score"),
                validToStr != null ? Instant.parse(validToStr) : null,
                updatedAtStr != null ? Instant.parse(updatedAtStr) : null);
    }
}
