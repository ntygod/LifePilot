package com.lifepilot.knowledge.index;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.exception.IndexingException;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.IndexingResult;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.util.KnowledgeQueryUtils;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量索引服务 — 批量向量化分块并存入 sqlite-vec（vectors.db）。
 *
 * <p>向量数据存储在独立的 vectors.db 中（通过 vectorJdbcTemplate），
 * 文档分块元数据存储在主数据库中（通过 mainJdbcTemplate）。
 * 检索时采用两步查询：先在 vectors.db 中做 KNN 搜索拿到 chunk_id + distance，
 * 再回主库查 document_chunks 详情。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexer.class);

    private final @Nullable EmbeddingRouter embeddingRouter;
    /** 向量数据库 JdbcTemplate（vectors.db，已加载 sqlite-vec 扩展） */
    private final JdbcTemplate vectorJdbcTemplate;
    /** 主数据库 JdbcTemplate（zhiwei.db，存储 document_chunks 等业务表） */
    private final JdbcTemplate mainJdbcTemplate;
    private final KnowledgeBaseProperties.VectorIndexer config;

    /**
     * 构造向量索引服务。
     *
     * @param embeddingRouter    向量路由器（可为 null，运行时动态配置）
     * @param vectorJdbcTemplate 向量数据库 JdbcTemplate（vectors.db）
     * @param mainJdbcTemplate   主数据库 JdbcTemplate（zhiwei.db）
     * @param config             向量索引配置
     */
    public VectorIndexer(@Nullable EmbeddingRouter embeddingRouter,
                         JdbcTemplate vectorJdbcTemplate,
                         JdbcTemplate mainJdbcTemplate,
                         KnowledgeBaseProperties.VectorIndexer config) {
        this.embeddingRouter = embeddingRouter;
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.mainJdbcTemplate = mainJdbcTemplate;
        this.config = config;

        // 在 vectors.db 中创建 chunk_embeddings vec0 虚拟表
        initVec0Table();

        log.info("VectorIndexer 初始化完成: batchSize={}, maxRetries={}, dimension={}, db=vectors.db, embeddingRouter={}",
                config.batchSize(), config.maxRetries(), config.embeddingDimension(),
                embeddingRouter != null ? "已配置" : "未配置");
    }

    /**
     * 在 vectors.db 中创建 chunk_embeddings vec0 虚拟表，sqlite-vec 不可用时跳过。
     *
     * <p>如果表已存在但维度与配置不一致，自动删除并重建（数据需重新索引）。
     */
    private void initVec0Table() {
        int targetDim = config.embeddingDimension();
        try {
            boolean needRecreate = false;
            try {
                vectorJdbcTemplate.execute(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_embeddings USING vec0(" +
                        "chunk_id TEXT PRIMARY KEY, " +
                        "embedding float[" + targetDim + "]" +
                        ")");
                // 用零向量探测维度是否匹配
                float[] probe = new float[targetDim];
                String probeVector = vectorToString(probe);
                vectorJdbcTemplate.update(
                        "INSERT INTO chunk_embeddings (chunk_id, embedding) VALUES (?, ?)",
                        "__dim_probe__", probeVector);
                vectorJdbcTemplate.update("DELETE FROM chunk_embeddings WHERE chunk_id = ?", "__dim_probe__");
            } catch (Exception probeEx) {
                String msg = probeEx.getMessage() != null ? probeEx.getMessage() : "";
                if (msg.contains("dimension") || msg.contains("Expected")) {
                    log.warn("向量索引: 检测到维度不匹配，将删除并重建 chunk_embeddings 表 (目标维度={})", targetDim);
                    needRecreate = true;
                } else {
                    throw probeEx;
                }
            }

            if (needRecreate) {
                vectorJdbcTemplate.execute("DROP TABLE IF EXISTS chunk_embeddings");
                vectorJdbcTemplate.execute(
                        "CREATE VIRTUAL TABLE chunk_embeddings USING vec0(" +
                        "chunk_id TEXT PRIMARY KEY, " +
                        "embedding float[" + targetDim + "]" +
                        ")");
                log.info("向量索引: chunk_embeddings 表已重建, dimension={} (已有向量数据需重新索引)", targetDim);
            } else {
                log.info("向量索引: chunk_embeddings vec0 表初始化完成, dimension={}", targetDim);
            }
        } catch (Exception e) {
            log.warn("向量索引: chunk_embeddings vec0 表创建失败，sqlite-vec 可能未加载", e);
        }
    }

    /**
     * 批量向量化分块并写入索引。
     *
     * @param chunks         待索引的分块列表
     * @param embeddingModel 嵌入模型名称（可选）
     * @return 索引结果
     * @throws IndexingException 所有重试均失败
     */
    public IndexingResult indexChunks(List<DocumentChunk> chunks, @Nullable String embeddingModel) {
        if (embeddingRouter == null) {
            log.warn("EmbeddingRouter 不可用，跳过向量索引: reason=路由器未配置");
            return new IndexingResult(0, 0, 0);
        }

        // Parent-Child 架构：只对 child 块（level=1）建向量索引；parent 块（level=0）不索引
        // 如果没有 child 块（非 Parent-Child 模式），则索引所有块
        boolean hasChildren = chunks.stream().anyMatch(c -> c.chunkLevel() == 1);
        var indexable = hasChildren
                ? chunks.stream().filter(c -> c.chunkLevel() == 1).toList()
                : chunks;

        if (indexable.isEmpty()) {
            return new IndexingResult(0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        int totalIndexed = 0;

        for (int i = 0; i < indexable.size(); i += config.batchSize()) {
            int end = Math.min(i + config.batchSize(), indexable.size());
            var batch = indexable.subList(i, end);
            indexBatchWithRetry(batch, embeddingModel);
            totalIndexed += batch.size();
            log.debug("向量索引批次完成: {}/{}", totalIndexed, indexable.size());
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
        vectorJdbcTemplate.update(sql, chunkIds.toArray());
        log.debug("删除向量索引: count={}", chunkIds.size());
    }

    /**
     * 删除指定文档的所有向量索引。
     *
     * <p>先从主库查询该文档的所有分块 ID，再从 vectors.db 中删除对应向量。
     *
     * @param documentId 文档 ID
     */
    public void removeByDocumentId(String documentId) {
        // 第一步：从主库查询该文档的所有分块 ID
        List<String> chunkIds = mainJdbcTemplate.queryForList(
                "SELECT id FROM document_chunks WHERE document_id = ?",
                String.class, documentId);
        if (chunkIds.isEmpty()) {
            log.debug("删除文档向量索引: documentId={}, 无分块需删除", documentId);
            return;
        }
        // 第二步：从 vectors.db 中批量删除
        removeChunkEmbeddings(chunkIds);
        log.debug("删除文档向量索引: documentId={}, deleted={}", documentId, chunkIds.size());
    }

    /**
     * 向量相似度搜索。
     *
     * @param query          查询文本
     * @param kbIds          知识库 ID 列表
     * @param topK           返回数量
     * @param embeddingModel 嵌入模型名称（可选）
     * @return 搜索结果列表（按相似度降序）
     */
    public List<DocumentSearchResult> searchSimilar(String query, List<String> kbIds, int topK,
                                                     @Nullable String embeddingModel) {
        if (embeddingRouter == null) {
            log.warn("EmbeddingRouter 不可用，跳过向量搜索: reason=路由器未配置");
            return List.of();
        }
        float[] queryVector = embeddingRouter.embed(query, EmbeddingUseCase.KNOWLEDGE_BASE, null, embeddingModel);
        return searchByEmbedding(queryVector, kbIds, topK);
    }

    /**
     * 向量相似度搜索，并按知识域范围过滤。
     */
    public List<DocumentSearchResult> searchSimilarByScopes(String query,
                                                            List<KnowledgeSearchScope> scopes,
                                                            int topK,
                                                            @Nullable String embeddingModel) {
        if (embeddingRouter == null) {
            log.warn("EmbeddingRouter 不可用，跳过向量搜索: reason=路由器未配置");
            return List.of();
        }
        float[] queryVector = embeddingRouter.embed(query, EmbeddingUseCase.KNOWLEDGE_BASE, null, embeddingModel);
        return searchByEmbeddingByScopes(queryVector, scopes, topK);
    }

    /**
     * 基于预计算 Embedding 向量的相似度搜索（用于 HyDE 模式）。
     *
     * <p>两步查询：先在 vectors.db 中做 KNN 搜索拿到 chunk_id + distance，
     * 再回主库查 document_chunks 详情并过滤知识库。
     *
     * @param embedding 预计算的 Embedding 向量
     * @param kbIds     知识库 ID 列表
     * @param topK      返回数量
     * @return 搜索结果列表（按相似度降序）
     */
    public List<DocumentSearchResult> searchByEmbedding(float[] embedding, List<String> kbIds, int topK) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return searchByEmbeddingByScopes(embedding, kbIds.stream()
                .map(kbId -> new KnowledgeSearchScope(kbId, null))
                .toList(), topK);
    }

    /**
     * 基于预计算 Embedding 向量的相似度搜索，并按知识域范围过滤。
     */
    public List<DocumentSearchResult> searchByEmbeddingByScopes(float[] embedding,
                                                                List<KnowledgeSearchScope> scopes,
                                                                int topK) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        String vectorParam = vectorToString(embedding);

        // 第一步：在 vectors.db 中做 KNN 搜索，多取一些候选（因为后续要按知识库过滤）
        int candidateK = topK * 3;
        var candidates = vectorJdbcTemplate.query(
                "SELECT chunk_id, distance FROM chunk_embeddings " +
                "WHERE embedding MATCH ? AND k = ? ORDER BY distance",
                (rs, rowNum) -> new VectorCandidate(
                        rs.getString("chunk_id"),
                        rs.getDouble("distance")),
                vectorParam, candidateK);

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 第二步：回主库查 document_chunks 详情并过滤知识库
        var chunkIds = candidates.stream().map(VectorCandidate::chunkId).toList();
        var distanceMap = new HashMap<String, Double>();
        for (var c : candidates) {
            distanceMap.put(c.chunkId(), c.distance());
        }

        var chunkPlaceholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        KnowledgeQueryUtils.ScopeSql scopeSql = KnowledgeQueryUtils.buildScopeSql("knowledge_base_id", "source_datastore_id", scopes);
        var sql = """
                SELECT id, document_id, knowledge_base_id, content, context_prefix,
                       heading_hierarchy_json, metadata_json,
                       source_type, source_datastore_id, source_collection_id
                FROM document_chunks
                WHERE id IN (%s)
                  AND (%s)""".formatted(chunkPlaceholders, scopeSql.sql());

        var params = new ArrayList<Object>();
        params.addAll(chunkIds);
        params.addAll(scopeSql.params());

        var results = mainJdbcTemplate.query(sql, (rs, rowNum) -> {
            String chunkId = rs.getString("id");
            double distance = distanceMap.getOrDefault(chunkId, 1.0);
            String headingJson = rs.getString("heading_hierarchy_json");
            List<String> headings = headingJson != null && !headingJson.isBlank()
                    ? KnowledgeQueryUtils.parseJsonList(headingJson) : List.of();
            return new DocumentSearchResult(
                    chunkId,
                    rs.getString("document_id"),
                    rs.getString("knowledge_base_id"),
                    rs.getString("content"),
                    Optional.ofNullable(rs.getString("context_prefix")),
                    headings,
                    1.0 - distance, // 距离转相似度
                    "vector",
                    Map.of(),
                    Optional.empty(),
                    Optional.empty(),
                    KnowledgeQueryUtils.parseSourceType(rs.getString("source_type")),
                    Optional.ofNullable(rs.getString("source_datastore_id")),
                    Optional.ofNullable(rs.getString("source_collection_id"))
            );
        }, params.toArray());

        // 按相似度降序排序，取 topK
        return results.stream()
                .sorted(Comparator.comparingDouble(DocumentSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    // ---- 内部方法 ----

    /** KNN 搜索候选结果。 */
    private record VectorCandidate(String chunkId, double distance) {}

    /**
     * 带重试的批量索引。
     */
    private void indexBatchWithRetry(List<DocumentChunk> batch, @Nullable String embeddingModel) {
        int maxAttempts = config.maxRetries() + 1;
        long delay = 500L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                indexBatch(batch, embeddingModel);
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
     * 执行单批次向量索引 — 批量 Embedding + 批量 INSERT。
     */
    private void indexBatch(List<DocumentChunk> batch, @Nullable String embeddingModel) {
        // 批量 Embedding
        List<String> texts = batch.stream().map(DocumentChunk::embeddingText).toList();
        float[][] embeddings = embeddingRouter.embedBatch(texts, EmbeddingUseCase.KNOWLEDGE_BASE, null, embeddingModel);

        // 批量 INSERT
        var sql = "INSERT INTO chunk_embeddings (chunk_id, embedding) VALUES (?, ?)";
        vectorJdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setString(1, batch.get(i).id());
                ps.setString(2, vectorToString(embeddings[i]));
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
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

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
