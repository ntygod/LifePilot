package com.lifepilot.memory.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

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
     * FTS5 全文搜索。
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
            String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(query);
            if (normalizedQuery.isBlank()) {
                return List.of();
            }
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
                    ORDER BY matched_sessions.score DESC, te.importance_score DESC, te.updated_at DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
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
                    },
                    normalizedQuery, topK);
        } catch (Exception e) {
            log.warn("全文搜索: 查询失败, query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }
}
