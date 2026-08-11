package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.store.support.MemoryQuerySignals;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

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

    private final JdbcTemplate jdbcTemplate;

    public FtsSearcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
    }

    /**
     * FTS5 全文搜索 — 双路合并：会话记录 FTS + 实体文本子串匹配。
     *
     * @param query 查询文本
     * @param topK  返回前 K 个结果
     * @return 排名条目列表
     */
    public List<RankedItem> search(String query, int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("全文搜索 topK 必须大于 0: " + topK);
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("全文搜索 query 不能为空");
        }
        String normalizedInput = query.trim();

        // 路径 1: 通过会话记录 FTS5 检索关联实体
        String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(normalizedInput);
        List<RankedItem> transcriptResults = normalizedQuery.isBlank()
                ? List.of()
                : searchViaTranscript(normalizedQuery, topK);

        // 路径 2: 实体名称/描述子串匹配（补充无 source_conversation_id 的实体）
        List<RankedItem> entityResults = searchEntityText(normalizedInput, topK);

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
    }

    /** 通过会话记录 FTS5 检索关联实体。 */
    private List<RankedItem> searchViaTranscript(String normalizedQuery, int topK) {
        return requireRankedItems(jdbcTemplate.query(
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
                normalizedQuery, topK), "全文搜索 transcript 查询结果");
    }

    /** 通过实体名称/描述子串匹配检索（补充 FTS 无法覆盖的无 source_conversation_id 实体）。 */
    private List<RankedItem> searchEntityText(String query, int topK) {
        List<String> lookupTerms = MemoryQuerySignals.lookupTerms(query);
        if (lookupTerms.isEmpty()) {
            return List.of();
        }
        String nameClause = MemoryQuerySignals.likeWhereClause("te.name", lookupTerms.size());
        String descriptionClause = MemoryQuerySignals.likeWhereClause("COALESCE(te.description, '')", lookupTerms.size());
        String sql = """
                SELECT te.id, te.type, te.name, te.description,
                       0.0 AS score,
                       te.last_accessed_at, te.importance_score, te.valid_to,
                       te.updated_at
                FROM temporal_entities te
                WHERE te.is_current = 1
                  AND (te.valid_to IS NULL OR te.valid_to > datetime('now'))
                  AND te.lifecycle_state NOT IN ('EXPIRED', 'SUPERSEDED', 'ARCHIVED', 'CANCELLED')
                  AND (%s OR %s)
                ORDER BY te.updated_at DESC, te.importance_score DESC
                LIMIT ?
                """.formatted(nameClause, descriptionClause);
        List<Object> args = new ArrayList<>();
        for (String term : lookupTerms) {
            args.add(MemoryQuerySignals.likePattern(term));
        }
        for (String term : lookupTerms) {
            args.add(MemoryQuerySignals.likePattern(term));
        }
        args.add(Math.max(topK * 4, topK));

        List<RankedItem> results = requireRankedItems(
                jdbcTemplate.query(sql, (rs, rowNum) -> mapRankedItem(rs), args.toArray()),
                "全文搜索实体文本查询结果");

        return results.stream()
                .map(item -> new RankedItem(
                        item.entityId(), item.entityType(), item.name(), item.description(),
                        MemoryQuerySignals.textMatchScore(query, item.name(), item.description()),
                        item.lastAccessedAt(), item.importanceScore(), item.validTo(), item.updatedAt()))
                .filter(item -> item.score() > 0.0f)
                .sorted(Comparator
                        .comparingDouble(RankedItem::score).reversed()
                        .thenComparing(RankedItem::importanceScore, Comparator.reverseOrder())
                        .thenComparing(RankedItem::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();
    }

    private List<RankedItem> requireRankedItems(List<RankedItem> results, String label) {
        if (results == null) {
            throw new IllegalStateException(label + "不能为空");
        }
        if (results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(label + "包含 null 条目");
        }
        return results;
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
