package com.lifepilot.knowledge.index;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.exception.IndexingException;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.IndexingResult;
import com.lifepilot.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量索引服务 — 批量向量化分块并存入 sqlite-vec。
 *
 * <p>使用 {@link LlmRouter#embed(String)} 生成向量，批量写入 chunk_embeddings 表。
 * 失败时指数退避重试，最多重试 {@code maxRetries} 次。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexer.class);

    private final LlmRouter llmRouter;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseProperties.VectorIndexer config;

    /**
     * 构造向量索引服务。
     *
     * @param llmRouter    LLM 路由器（用于 Embedding）
     * @param jdbcTemplate JDBC 模板
     * @param config       向量索引配置
     */
    public VectorIndexer(LlmRouter llmRouter, JdbcTemplate jdbcTemplate,
                         KnowledgeBaseProperties.VectorIndexer config) {
        this.llmRouter = llmRouter;
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;

        // 程序化创建 chunk_embeddings vec0 虚拟表（sqlite-vec 可用时）
        initVec0Table();

        log.info("VectorIndexer 初始化完成: batchSize={}, maxRetries={}, dimension={}",
                config.batchSize(), config.maxRetries(), config.embeddingDimension());
    }

    /** 程序化创建 chunk_embeddings vec0 虚拟表，sqlite-vec 不可用时跳过。 */
    private void initVec0Table() {
        try {
            jdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_embeddings USING vec0(" +
                    "chunk_id TEXT PRIMARY KEY, " +
                    "embedding float[" + config.embeddingDimension() + "]" +
                    ")");
            log.info("向量索引: chunk_embeddings vec0 表初始化完成, dimension={}", config.embeddingDimension());
        } catch (Exception e) {
            log.warn("向量索引: chunk_embeddings vec0 表创建失败，sqlite-vec 可能未加载", e);
        }
    }

    /**
     * 批量向量化分块并写入索引。
     *
     * <p>按 batchSize 分批调用 Embedding API，每个分块使用 {@link DocumentChunk#embeddingText()}
     * 作为输入。失败时指数退避重试。
     *
     * @param chunks 待索引的分块列表
     * @return 索引结果
     * @throws IndexingException 所有重试均失败
     */
    public IndexingResult indexChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return new IndexingResult(0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        int totalIndexed = 0;

        // 按 batchSize 分批处理
        for (int i = 0; i < chunks.size(); i += config.batchSize()) {
            int end = Math.min(i + config.batchSize(), chunks.size());
            var batch = chunks.subList(i, end);
            indexBatchWithRetry(batch);
            totalIndexed += batch.size();
            log.debug("向量索引批次完成: {}/{}", totalIndexed, chunks.size());
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("向量索引完成: count={}, durationMs={}", totalIndexed, durationMs);
        return new IndexingResult(totalIndexed, 0, durationMs);
    }

    /**
     * 删除指定分块的向量索引。
     *
     * @param chunkIds 分块 ID 列表
     */
    public void removeChunkEmbeddings(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return;
        }
        var placeholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        var sql = "DELETE FROM chunk_embeddings WHERE chunk_id IN (" + placeholders + ")";
        jdbcTemplate.update(sql, chunkIds.toArray());
        log.debug("删除向量索引: count={}", chunkIds.size());
    }

    /**
     * 删除指定文档的所有向量索引。
     *
     * @param documentId 文档 ID
     */
    public void removeByDocumentId(String documentId) {
        // 通过子查询找到该文档的所有分块 ID 并删除
        var sql = """
                DELETE FROM chunk_embeddings
                WHERE chunk_id IN (
                    SELECT id FROM document_chunks WHERE document_id = ?
                )""";
        int deleted = jdbcTemplate.update(sql, documentId);
        log.debug("删除文档向量索引: documentId={}, deleted={}", documentId, deleted);
    }

    /**
     * 向量相似度搜索。
     *
     * @param query 查询文本
     * @param kbIds 知识库 ID 列表
     * @param topK  返回数量
     * @return 搜索结果列表（按相似度降序）
     */
    public List<DocumentSearchResult> searchSimilar(String query, List<String> kbIds, int topK) {
        // 生成查询向量并转为 sqlite-vec 格式
        float[] queryVector = llmRouter.embed(query);
        String vectorParam = vectorToString(queryVector);

        // sqlite-vec KNN 查询 + 联合 document_chunks 表过滤知识库
        var kbPlaceholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
        var sql = """
                SELECT ce.chunk_id, ce.distance,
                       dc.document_id, dc.knowledge_base_id, dc.content, dc.context_prefix,
                       dc.heading_hierarchy_json, dc.metadata_json
                FROM chunk_embeddings ce
                JOIN document_chunks dc ON ce.chunk_id = dc.id
                WHERE ce.embedding MATCH ?
                  AND ce.k = ?
                  AND dc.knowledge_base_id IN (%s)
                ORDER BY ce.distance""".formatted(kbPlaceholders);

        var params = new ArrayList<Object>();
        params.add(vectorParam);
        params.add(topK);
        params.addAll(kbIds);

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
                    1.0 - rs.getDouble("distance"), // 距离转相似度
                    "vector",
                    Map.of()
            );
        }, params.toArray());
    }

    // ---- 内部方法 ----

    /**
     * 带重试的批量索引。
     */
    private void indexBatchWithRetry(List<DocumentChunk> batch) {
        int maxAttempts = config.maxRetries() + 1;
        long delay = 500L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                indexBatch(batch);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw new IndexingException(
                            "向量索引失败，已重试 %d 次: %s".formatted(config.maxRetries(), e.getMessage()), e);
                }
                log.warn("向量索引批次失败，第 {} 次重试: error={}", attempt, e.getMessage());
                sleep(delay);
                delay = Math.min(delay * 2, 5000L);
            }
        }
    }

    /**
     * 执行单批次向量索引。
     */
    private void indexBatch(List<DocumentChunk> batch) {
        var sql = "INSERT INTO chunk_embeddings (chunk_id, embedding) VALUES (?, ?)";
        for (var chunk : batch) {
            float[] embedding = llmRouter.embed(chunk.embeddingText());
            var vectorStr = vectorToString(embedding);
            jdbcTemplate.update(sql, chunk.id(), vectorStr);
        }
    }

    /**
     * float 数组转 sqlite-vec 格式字符串。
     */
    private String vectorToString(float[] vector) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 简单 JSON 数组解析（用于 heading_hierarchy_json）。
     */
    private List<String> parseJsonList(String json) {
        // 简单解析 ["a","b","c"] 格式
        if (json == null || json.equals("[]")) return List.of();
        var content = json.substring(1, json.length() - 1);
        if (content.isBlank()) return List.of();
        return Arrays.stream(content.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .toList();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
