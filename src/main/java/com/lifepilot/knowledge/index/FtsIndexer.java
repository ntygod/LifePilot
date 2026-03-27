package com.lifepilot.knowledge.index;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.IndexingResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FTS5 全文索引服务 — 构建和维护 SQLite FTS5 全文索引。
 *
 * <p>FTS5 虚拟表 {@code document_chunks_fts} 通过触发器自动与 {@code document_chunks}
 * 表同步，本服务提供手动索引（用于已有数据补建）和搜索功能。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FtsIndexer {

    private static final Logger log = LoggerFactory.getLogger(FtsIndexer.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造 FTS5 索引服务。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public FtsIndexer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        log.info("FtsIndexer 初始化完成");
    }

    /**
     * 索引分块到 FTS5。
     *
     * <p>注意：如果分块已通过 {@code document_chunks} 表的 INSERT 触发器自动同步到 FTS5，
     * 则无需手动调用此方法。此方法用于补建索引或手动同步场景。
     *
     * @param chunks 待索引的分块列表
     * @return 索引结果
     */
    public IndexingResult indexChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return new IndexingResult(0, 0, 0);
        }

        long startTime = System.currentTimeMillis();

        // FTS5 通过触发器自动同步，此处为补建索引场景
        // 先查询 document_chunks 表获取 rowid，再手动插入 FTS5
        var sql = """
                INSERT INTO document_chunks_fts(rowid, content, knowledge_base_id, document_id, chunk_id)
                SELECT rowid, content, knowledge_base_id, document_id, id
                FROM document_chunks WHERE id = ?""";

        int indexed = 0;
        for (var chunk : chunks) {
            try {
                jdbcTemplate.update(sql, chunk.id());
                indexed++;
            } catch (Exception e) {
                // FTS5 重复插入会报错，跳过已存在的
                log.debug("FTS5 索引跳过（可能已存在）: chunkId={}, error={}", chunk.id(), e.getMessage());
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("FTS5 索引完成: count={}, durationMs={}", indexed, durationMs);
        return new IndexingResult(0, indexed, durationMs);
    }

    /**
     * 删除指定文档的所有 FTS5 索引。
     *
     * <p>注意：如果通过 {@code document_chunks} 表的 DELETE 触发器自动同步，
     * 则无需手动调用。此方法用于手动清理场景。
     *
     * @param documentId 文档 ID
     */
    public void removeByDocumentId(String documentId) {
        // 通过 FTS5 的 delete 命令删除
        var sql = """
                INSERT INTO document_chunks_fts(document_chunks_fts, rowid, content, knowledge_base_id, document_id, chunk_id)
                SELECT 'delete', dc.rowid, dc.content, dc.knowledge_base_id, dc.document_id, dc.id
                FROM document_chunks dc WHERE dc.document_id = ?""";
        int deleted = jdbcTemplate.update(sql, documentId);
        log.debug("删除文档 FTS5 索引: documentId={}, deleted={}", documentId, deleted);
    }

    /**
     * FTS5 全文搜索。
     *
     * <p>使用 BM25 排序，返回匹配的分块列表。
     *
     * @param query 搜索查询
     * @param kbIds 知识库 ID 列表
     * @param topK  返回数量
     * @return 搜索结果列表（按 BM25 相关性降序）
     */
    public List<DocumentSearchResult> search(String query, List<String> kbIds, int topK) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return searchByScopes(query, kbIds.stream()
                .map(kbId -> new KnowledgeSearchScope(kbId, null))
                .toList(), topK);
    }

    /**
     * FTS5 全文搜索，并按知识域范围过滤。
     */
    public List<DocumentSearchResult> searchByScopes(String query, List<KnowledgeSearchScope> scopes, int topK) {
        if (query == null || query.isBlank() || scopes == null || scopes.isEmpty()) {
            return List.of();
        }

        ScopeSql scopeSql = buildScopeSql("fts.knowledge_base_id", "dc.source_datastore_id", scopes);
        var sql = """
                SELECT fts.chunk_id, fts.document_id, fts.knowledge_base_id, fts.content,
                       dc.context_prefix, dc.heading_hierarchy_json, dc.metadata_json,
                       dc.source_type, dc.source_datastore_id, dc.source_collection_id,
                       rank AS score
                FROM document_chunks_fts fts
                JOIN document_chunks dc ON fts.chunk_id = dc.id
                WHERE document_chunks_fts MATCH ?
                  AND (%s)
                ORDER BY rank
                LIMIT ?""".formatted(scopeSql.sql());

        var params = new ArrayList<Object>();
        params.add(escapeFtsQuery(query));
        params.addAll(scopeSql.params());
        params.add(topK);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String headingJson = rs.getString("heading_hierarchy_json");
            List<String> headings = headingJson != null && !headingJson.isBlank()
                    ? parseJsonList(headingJson) : List.of();
            return new DocumentSearchResult(
                    rs.getString("chunk_id"),
                    rs.getString("document_id"),
                    rs.getString("knowledge_base_id"),
                    rs.getString("content"),
                    Optional.ofNullable(rs.getString("context_prefix")),
                    headings,
                    Math.abs(rs.getDouble("score")), // FTS5 rank 为负数，取绝对值
                    "fts",
                    Map.of(),
                    Optional.empty(),
                    Optional.empty(),
                    parseSourceType(rs.getString("source_type")),
                    Optional.ofNullable(rs.getString("source_datastore_id")),
                    Optional.ofNullable(rs.getString("source_collection_id"))
            );
        }, params.toArray());
    }

    // ---- 内部方法 ----

    /**
     * 转义 FTS5 查询中的特殊字符。
     */
    private String escapeFtsQuery(String query) {
        // FTS5 特殊字符：双引号包裹整个查询以避免语法错误
        return "\"" + query.replace("\"", "\"\"") + "\"";
    }

    /**
     * 简单 JSON 数组解析。
     */
    private List<String> parseJsonList(String json) {
        if (json == null || json.equals("[]")) return List.of();
        var content = json.substring(1, json.length() - 1);
        if (content.isBlank()) return List.of();
        return Arrays.stream(content.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .toList();
    }

    private ScopeSql buildScopeSql(String kbColumn, String datastoreColumn, List<KnowledgeSearchScope> scopes) {
        var sqlParts = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (KnowledgeSearchScope scope : scopes) {
            if (scope == null || scope.knowledgeBaseId() == null || scope.knowledgeBaseId().isBlank()) {
                continue;
            }
            if (scope.datastoreId() == null || scope.datastoreId().isBlank()) {
                sqlParts.add(kbColumn + " = ?");
                params.add(scope.knowledgeBaseId());
            } else {
                sqlParts.add("(" + kbColumn + " = ? AND " + datastoreColumn + " = ?)");
                params.add(scope.knowledgeBaseId());
                params.add(scope.datastoreId());
            }
        }
        return new ScopeSql(String.join(" OR ", sqlParts), params);
    }

    private DocumentSourceType parseSourceType(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DocumentSourceType.FILE;
        }
        try {
            return DocumentSourceType.valueOf(rawValue);
        } catch (IllegalArgumentException e) {
            log.warn("未知检索来源类型，回退 FILE: value={}", rawValue);
            return DocumentSourceType.FILE;
        }
    }

    private record ScopeSql(String sql, List<Object> params) {}
}
