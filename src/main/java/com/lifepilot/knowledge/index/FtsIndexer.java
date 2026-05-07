package com.lifepilot.knowledge.index;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.IndexingResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.util.KnowledgeQueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

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
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("UNIQUE") || msg.contains("constraint") || msg.contains("already exists")) {
                    log.debug("FTS5 索引跳过（已存在）: chunkId={}", chunk.id());
                } else {
                    log.warn("FTS5 索引失败: chunkId={}, error={}", chunk.id(), msg);
                }
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
                .map(KnowledgeSearchScope::new)
                .toList(), topK);
    }

    /**
     * FTS5 全文搜索，并按知识域范围过滤。
     */
    public List<DocumentSearchResult> searchByScopes(String query, List<KnowledgeSearchScope> scopes, int topK) {
        if (query == null || query.isBlank() || scopes == null || scopes.isEmpty()) {
            return List.of();
        }

        KnowledgeQueryUtils.ScopeSql scopeSql = KnowledgeQueryUtils.buildScopeSql("dc.knowledge_base_id", scopes);
        var sql = """
                SELECT dc.id AS chunk_id, dc.document_id, dc.knowledge_base_id, dc.content,
                       dc.context_prefix, dc.heading_hierarchy_json, dc.metadata_json,
                       dc.source_type, rank AS score
                FROM document_chunks_fts fts
                JOIN document_chunks dc ON fts.rowid = dc.rowid
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
                    ? KnowledgeQueryUtils.parseJsonList(headingJson) : List.of();
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
                    KnowledgeQueryUtils.parseSourceType(rs.getString("source_type"))
            );
        }, params.toArray());
    }

    // ---- 内部方法 ----

    /**
     * 构建 FTS5 MATCH 查询表达式。
     *
     * <p>使用 trigram tokenizer，将查询按空格拆分为独立片段并用 OR 连接，
     * 提升多词查询的召回率。例如 "Spring Boot 配置" → {@code "Spring" OR "Boot" OR "配置"}。
     * 无空格的纯中文查询保持为单个子串匹配。过滤 < 2 字符的碎片避免噪声。
     */
    private String escapeFtsQuery(String query) {
        // 先移除所有双引号，后续包裹时不需要再转义
        String cleaned = query.replace("\"", "").strip();
        if (cleaned.isEmpty()) {
            return "\"\"";
        }
        String[] parts = cleaned.split("\\s+");
        if (parts.length <= 1) {
            return "\"" + cleaned + "\"";
        }
        // 多片段：按空格拆分，OR 连接，过滤太短的碎片
        var terms = new ArrayList<String>();
        for (String part : parts) {
            if (part.length() >= 2) {
                terms.add("\"" + part + "\"");
            }
        }
        if (terms.isEmpty()) {
            return "\"" + cleaned + "\"";
        }
        return String.join(" OR ", terms);
    }

}
