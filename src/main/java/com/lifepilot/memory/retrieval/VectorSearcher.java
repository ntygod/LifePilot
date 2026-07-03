package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 向量语义检索器 — 基于 sqlite-vec 的实体向量检索。
 *
 * <p>初始化时程序化创建 entity_embeddings vec0 虚拟表。sqlite-vec 或 EmbeddingRouter
 * 不可用时直接失败，避免向量索引静默缺失。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    private final JdbcTemplate vectorJdbcTemplate;
    private final EmbeddingRouter embeddingRouter;
    private final boolean vecExtensionLoaded;
    private final int embeddingDimensions;

    /**
     * 构造 VectorSearcher。
     *
     * @param vectorJdbcTemplate 向量数据库 JdbcTemplate
     * @param embeddingRouter    向量路由器
     * @param vecExtensionLoaded sqlite-vec 扩展是否已加载
     * @param embeddingDimensions 向量维度
     */
    public VectorSearcher(JdbcTemplate vectorJdbcTemplate,
                          EmbeddingRouter embeddingRouter,
                          boolean vecExtensionLoaded,
                          int embeddingDimensions) {
        this.vectorJdbcTemplate = Objects.requireNonNull(vectorJdbcTemplate, "vectorJdbcTemplate 不能为空");
        this.embeddingRouter = Objects.requireNonNull(embeddingRouter, "embeddingRouter 不能为空");
        this.vecExtensionLoaded = vecExtensionLoaded;
        this.embeddingDimensions = embeddingDimensions;
        if (embeddingDimensions <= 0) {
            throw new IllegalArgumentException("embeddingDimensions 必须大于 0: " + embeddingDimensions);
        }
        if (!vecExtensionLoaded) {
            throw new IllegalStateException("sqlite-vec 扩展未加载，无法启用向量检索");
        }

        initVec0Table();
    }

    /** 程序化创建 entity_embeddings vec0 虚拟表。 */
    private void initVec0Table() {
        vectorJdbcTemplate.execute(
                "CREATE VIRTUAL TABLE IF NOT EXISTS entity_embeddings USING vec0(" +
                "entity_id TEXT PRIMARY KEY, " +
                "embedding FLOAT[" + embeddingDimensions + "]" +
                ")");
        log.info("向量检索: entity_embeddings vec0 表初始化完成, dimensions={}", embeddingDimensions);
    }

    /**
     * 向量检索：sqlite-vec KNN 搜索。
     *
     * @param queryText 查询文本
     * @param topK      返回前 K 个结果
     * @param threshold 相似度阈值
     * @return 检索结果列表
     */
    public List<VectorSearchResult> searchEntities(String queryText, int topK, float threshold) {
        validateSearchArguments(queryText, topK, threshold);
        float[] queryVector = embedRequired(queryText, "向量检索查询");
        return searchWithVec(queryVector, topK, threshold);
    }

    /**
     * 带候选过滤集的向量检索 — 仅返回 eligibleIds 中的实体。
     *
     * <p>当 filter 限制了 space_id / memory_scope 时，由 HybridRetriever 预查主库获取合规 ID 集合，
     * 然后在向量检索结果上做内存过滤，避免返回大量不合规候选。</p>
     *
     * @param queryText   查询文本
     * @param topK        返回前 K 个结果
     * @param threshold   相似度阈值
     * @param eligibleIds 合规实体 ID 集合，null 表示不过滤
     * @return 检索结果列表
     */
    public List<VectorSearchResult> searchEntities(String queryText, int topK, float threshold,
                                                    Set<String> eligibleIds) {
        validateSearchArguments(queryText, topK, threshold);
        validateEligibleIds(eligibleIds);
        if (eligibleIds != null && eligibleIds.isEmpty()) {
            return List.of();
        }
        int effectiveTopK = eligibleIds != null ? Math.multiplyExact(topK, 3) : topK;
        float[] queryVector = embedRequired(queryText, "向量检索查询");
        var results = searchWithVec(queryVector, effectiveTopK, threshold);
        if (eligibleIds != null) {
            results = results.stream()
                    .filter(r -> eligibleIds.contains(r.entityId()))
                    .limit(topK)
                    .toList();
        }
        return results;
    }

    /** sqlite-vec KNN 搜索。 */
    private List<VectorSearchResult> searchWithVec(float[] queryVector, int topK, float threshold) {
        // vec_distance_cosine 返回余弦距离 [0, 2]，转换为相似度 = 1 - distance / 2
        byte[] vectorBytes = floatArrayToBytes(queryVector);
        List<VectorSearchResult> results = vectorJdbcTemplate.query(
                "SELECT entity_id, vec_distance_cosine(embedding, ?) AS distance " +
                "FROM entity_embeddings ORDER BY distance LIMIT ?",
                (rs, rowNum) -> {
                    float distance = rs.getFloat("distance");
                    if (!Float.isFinite(distance) || distance < 0.0f || distance > 2.0f) {
                        throw new IllegalStateException("sqlite-vec 返回非法余弦距离: " + distance);
                    }
                    float similarity = 1.0f - distance / 2.0f;
                    return new VectorSearchResult(rs.getString("entity_id"), similarity);
                },
                vectorBytes, topK
        );
        if (results == null) {
            throw new IllegalStateException("向量检索 SQL 查询返回 null");
        }
        if (results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("向量检索 SQL 查询返回 null 元素");
        }
        return results.stream()
                .filter(r -> r.similarity() >= threshold)
                .toList();
    }

    /**
     * 插入/更新实体向量：文本 → Embedding → entity_embeddings 表。
     *
     * @param entityId 实体 ID
     * @param text     文本内容
     */
    public void upsertEntityVector(String entityId, String text) {
        validateEntityId(entityId);
        validateText(text, "向量写入文本");
        float[] vector = embedRequired(text, "实体向量写入");
        byte[] vectorBytes = floatArrayToBytes(vector);
        // vec0 虚拟表不支持 INSERT OR REPLACE，需先 DELETE 再 INSERT
        vectorJdbcTemplate.update(
                "DELETE FROM entity_embeddings WHERE entity_id = ?", entityId);
        vectorJdbcTemplate.update(
                "INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)",
                entityId, vectorBytes);
        log.debug("向量检索: 更新实体向量, entityId={}", entityId);
    }

    /**
     * 删除实体向量。
     *
     * @param entityId 实体 ID
     */
    public void deleteEntityVector(String entityId) {
        validateEntityId(entityId);
        vectorJdbcTemplate.update(
                "DELETE FROM entity_embeddings WHERE entity_id = ?", entityId);
        log.debug("向量检索: 删除实体向量, entityId={}", entityId);
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
        validateVector(floats, "向量字节序列化");
        var buffer = java.nio.ByteBuffer.allocate(floats.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private void validateSearchArguments(String queryText, int topK, float threshold) {
        validateText(queryText, "向量检索查询文本");
        if (topK <= 0) {
            throw new IllegalArgumentException("向量检索 topK 必须大于 0: " + topK);
        }
        if (!Float.isFinite(threshold) || threshold < 0.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("向量检索 threshold 必须在 [0,1] 范围内: " + threshold);
        }
    }

    private void validateEligibleIds(Set<String> eligibleIds) {
        if (eligibleIds == null) {
            return;
        }
        for (String id : eligibleIds) {
            validateEntityId(id);
        }
    }

    private void validateEntityId(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("向量检索 entityId 不能为空");
        }
        if (!entityId.equals(entityId.trim())) {
            throw new IllegalArgumentException("向量检索 entityId 不能包含首尾空白: " + entityId);
        }
    }

    private void validateText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    private float[] embedRequired(String text, String pathName) {
        float[] vector = embeddingRouter.embed(text, EmbeddingUseCase.MEMORY, null, null);
        validateVector(vector, pathName);
        return vector;
    }

    private void validateVector(float[] vector, String pathName) {
        if (vector == null) {
            throw new IllegalStateException(pathName + "返回 null 向量");
        }
        if (vector.length != embeddingDimensions) {
            throw new IllegalStateException(pathName + "返回向量维度不匹配: expected="
                    + embeddingDimensions + ", actual=" + vector.length);
        }
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalStateException(pathName + "返回非法向量值: index=" + i + ", value=" + vector[i]);
            }
        }
    }

}
