package com.lifepilot.memory.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/**
 * FTS5 全文搜索器 — 基于 SQLite FTS5 + BM25 的消息和实体关键词检索。
 *
 * <p>通过 messages_fts 全文索引检索匹配消息，再通过 source_conversation_id
 * 关联到 temporal_entities，返回相关实体的排名列表。</p>
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
            String escapedQuery = escapeFts5Query(query);
            if (escapedQuery.isBlank()) {
                return List.of();
            }
            return jdbcTemplate.query(
                    """
                    SELECT te.id, te.type, te.name, te.description,
                           -bm25(messages_fts) AS score,
                           te.last_accessed_at, te.importance_score, te.valid_to,
                           te.updated_at
                    FROM messages_fts
                    JOIN messages m ON messages_fts.rowid = m.rowid
                    JOIN temporal_entities te ON te.source_conversation_id = m.conversation_id
                    WHERE messages_fts MATCH ?
                      AND te.is_current = 1
                      AND (te.valid_to IS NULL OR te.valid_to > datetime('now'))
                    GROUP BY te.id
                    ORDER BY score DESC
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
                    escapedQuery, topK);
        } catch (Exception e) {
            log.warn("全文搜索: 查询失败, query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 转义 FTS5 特殊字符，防止语法错误。
     * 将查询文本中的特殊字符移除或转义，保留有效的搜索词。
     */
    private String escapeFts5Query(String query) {
        // 移除 FTS5 特殊字符和操作符
        String cleaned = query
                .replace("\"", " ")
                .replace("*", " ")
                .replace("^", " ")
                .replace("(", " ")
                .replace(")", " ")
                .replace("{", " ")
                .replace("}", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace(":", " ")
                .replace(",", " ")
                .replace(";", " ")
                .replace("!", " ")
                .replace("?", " ")
                .replace("+", " ")
                .replace("-", " ")
                .replace("~", " ")
                .replace("@", " ")
                .replace("#", " ")
                .replace("$", " ")
                .replace("%", " ")
                .replace("&", " ")
                .replace("=", " ")
                .replace("<", " ")
                .replace(">", " ")
                .replace("/", " ")
                .replace("\\", " ")
                .replace("|", " ")
                .replace("'", " ");

        // 移除 FTS5 布尔操作符（作为独立词出现时）
        String[] tokens = cleaned.split("\\s+");
        var sb = new StringBuilder();
        for (String token : tokens) {
            String upper = token.toUpperCase();
            if (upper.equals("AND") || upper.equals("OR") || upper.equals("NOT") || upper.equals("NEAR")) {
                continue;
            }
            if (!token.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(token);
            }
        }
        return sb.toString().trim();
    }
}
