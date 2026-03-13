package com.lifepilot.memory.retrieval;

import com.lifepilot.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Comparator;
import java.util.List;

/**
 * 向量语义检索器 — 基于 sqlite-vec 的实体向量检索，支持降级为 JVM 暴力搜索。
 *
 * <p>初始化时程序化创建 entity_embeddings vec0 虚拟表（sqlite-vec 可用时）。
 * sqlite-vec 不可用时降级为 JVM 暴力搜索，保证功能等价。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    private final JdbcTemplate vectorJdbcTemplate;
    private final LlmRouter llmRouter;
    private final boolean vecExtensionLoaded;
    private final int embeddingDimensions;

    /**
     * 构造 VectorSearcher。
     *
     * @param vectorJdbcTemplate 向量数据库 JdbcTemplate
     * @param llmRouter          LLM 路由器（用于 embed）
     * @param vecExtensionLoaded sqlite-vec 扩展是否已加载
     * @param embeddingDimensions 向量维度
     */
    public VectorSearcher(JdbcTemplate vectorJdbcTemplate,
                          LlmRouter llmRouter,
                          boolean vecExtensionLoaded,
                          int embeddingDimensions) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.llmRouter = llmRouter;
        this.vecExtensionLoaded = vecExtensionLoaded;
        this.embeddingDimensions = embeddingDimensions;

        // 程序化创建 entity_embeddings vec0 虚拟表
        if (vecExtensionLoaded) {
            initVec0Table();
        } else {
            log.debug("向量检索: sqlite-vec 未加载，跳过 vec0 表创建");
        }
    }

    /** 程序化创建 entity_embeddings vec0 虚拟表。 */
    private void initVec0Table() {
        try {
            vectorJdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS entity_embeddings USING vec0(" +
                    "entity_id TEXT PRIMARY KEY, " +
                    "embedding FLOAT[" + embeddingDimensions + "]" +
                    ")");
            log.info("向量检索: entity_embeddings vec0 表初始化完成, dimensions={}", embeddingDimensions);
        } catch (Exception e) {
            log.warn("向量检索: vec0 表创建失败，将降级为 JVM 暴力搜索", e);
        }
    }

    /**
     * 向量检索：sqlite-vec KNN 搜索，降级为 JVM 暴力搜索。
     *
     * @param queryText 查询文本
     * @param topK      返回前 K 个结果
     * @param threshold 相似度阈值
     * @return 检索结果列表
     */
    public List<VectorSearchResult> searchEntities(String queryText, int topK, float threshold) {
        try {
            float[] queryVector = llmRouter.embed(queryText);
            if (vecExtensionLoaded) {
                return searchWithVec(queryVector, topK, threshold);
            }
            return searchWithJvmFallback(queryVector, topK, threshold);
        } catch (Exception e) {
            log.warn("向量检索: 检索异常，降级为 JVM 暴力搜索, error={}", e.getMessage());
            try {
                float[] queryVector = llmRouter.embed(queryText);
                return searchWithJvmFallback(queryVector, topK, threshold);
            } catch (Exception fallbackEx) {
                log.warn("向量检索: JVM 暴力搜索也失败, error={}", fallbackEx.getMessage());
                return List.of();
            }
        }
    }

    /** sqlite-vec KNN 搜索。 */
    private List<VectorSearchResult> searchWithVec(float[] queryVector, int topK, float threshold) {
        // vec_distance_cosine 返回余弦距离 [0, 2]，转换为相似度 = 1 - distance / 2
        byte[] vectorBytes = floatArrayToBytes(queryVector);
        return vectorJdbcTemplate.query(
                "SELECT entity_id, vec_distance_cosine(embedding, ?) AS distance " +
                "FROM entity_embeddings ORDER BY distance LIMIT ?",
                (rs, rowNum) -> {
                    float distance = rs.getFloat("distance");
                    float similarity = 1.0f - distance / 2.0f;
                    return new VectorSearchResult(rs.getString("entity_id"), similarity);
                },
                vectorBytes, topK
        ).stream()
                .filter(r -> r.similarity() >= threshold)
                .toList();
    }

    /** JVM 暴力搜索降级：遍历所有当前实体向量计算余弦相似度。 */
    private List<VectorSearchResult> searchWithJvmFallback(float[] queryVector, int topK, float threshold) {
        log.debug("向量检索: 使用 JVM 暴力搜索降级");
        if (!vecExtensionLoaded) {
            // 无向量数据可搜索
            return List.of();
        }
        try {
            var allVectors = vectorJdbcTemplate.query(
                    "SELECT entity_id, embedding FROM entity_embeddings",
                    (rs, rowNum) -> {
                        String entityId = rs.getString("entity_id");
                        byte[] embeddingBytes = rs.getBytes("embedding");
                        float[] embedding = bytesToFloatArray(embeddingBytes);
                        float similarity = cosineSimilarity(queryVector, embedding);
                        return new VectorSearchResult(entityId, similarity);
                    }
            );
            return allVectors.stream()
                    .filter(r -> r.similarity() >= threshold)
                    .sorted(Comparator.comparingDouble(VectorSearchResult::similarity).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("向量检索: JVM 暴力搜索失败, error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 插入/更新实体向量：文本 → Embedding → entity_embeddings 表。
     *
     * @param entityId 实体 ID
     * @param text     文本内容
     */
    public void upsertEntityVector(String entityId, String text) {
        if (!vecExtensionLoaded) {
            log.debug("向量检索: sqlite-vec 未加载，跳过向量索引更新, entityId={}", entityId);
            return;
        }
        try {
            float[] vector = llmRouter.embed(text);
            byte[] vectorBytes = floatArrayToBytes(vector);
            // vec0 表使用 INSERT OR REPLACE
            vectorJdbcTemplate.update(
                    "INSERT OR REPLACE INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)",
                    entityId, vectorBytes);
            log.debug("向量检索: 更新实体向量, entityId={}", entityId);
        } catch (Exception e) {
            log.warn("向量检索: 向量索引更新失败, entityId={}, error={}", entityId, e.getMessage());
        }
    }

    /**
     * 删除实体向量。
     *
     * @param entityId 实体 ID
     */
    public void deleteEntityVector(String entityId) {
        if (!vecExtensionLoaded) {
            log.debug("向量检索: sqlite-vec 未加载，跳过向量删除, entityId={}", entityId);
            return;
        }
        try {
            vectorJdbcTemplate.update(
                    "DELETE FROM entity_embeddings WHERE entity_id = ?", entityId);
            log.debug("向量检索: 删除实体向量, entityId={}", entityId);
        } catch (Exception e) {
            log.warn("向量检索: 向量删除失败, entityId={}, error={}", entityId, e.getMessage());
        }
    }

    /**
     * 检查 sqlite-vec 扩展是否已加载。
     *
     * @return true 如果扩展已加载，false 否则
     */
    public boolean isVecExtensionLoaded() {
        return vecExtensionLoaded;
    }

    // --- 工具方法 ---

    /** float[] 转 byte[]（小端序，sqlite-vec 要求）。 */
    private byte[] floatArrayToBytes(float[] floats) {
        var buffer = java.nio.ByteBuffer.allocate(floats.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /** byte[] 转 float[]（小端序）。 */
    private float[] bytesToFloatArray(byte[] bytes) {
        var buffer = java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    /** 计算余弦相似度。 */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0f;
        float dotProduct = 0.0f, normA = 0.0f, normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denominator == 0.0f ? 0.0f : dotProduct / denominator;
    }
}
