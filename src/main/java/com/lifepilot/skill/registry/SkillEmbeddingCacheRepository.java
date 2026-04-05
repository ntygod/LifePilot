package com.lifepilot.skill.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Skill 向量缓存仓库 — 持久化 embedding 到 SQLite，避免重启后重复调用向量 API。
 *
 * <p>缓存键为 {@code skill_id + content_hash}，内容未变时直接读取缓存。
 * 写入失败不影响主流程，降级为实时计算。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class SkillEmbeddingCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingCacheRepository.class);

    private static final String FIND_SQL = """
            SELECT embedding FROM skill_embedding_cache
            WHERE skill_id = ? AND content_hash = ?
            """;

    private static final String UPSERT_SQL = """
            INSERT OR REPLACE INTO skill_embedding_cache (skill_id, content_hash, embedding, created_at)
            VALUES (?, ?, ?, datetime('now'))
            """;

    private static final String DELETE_SQL = """
            DELETE FROM skill_embedding_cache WHERE skill_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public SkillEmbeddingCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查找缓存的向量，内容哈希匹配时返回，否则返回 null。
     *
     * @param skillId     Skill ID
     * @param contentHash 搜索文本的 SHA-256 哈希
     * @return 缓存的向量，未命中或读取失败时返回 null
     */
    @Nullable
    public float[] find(String skillId, String contentHash) {
        try {
            var results = jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> {
                byte[] blob = rs.getBytes("embedding");
                return deserializeVector(blob);
            }, skillId, contentHash);
            return results.isEmpty() ? null : results.getFirst();
        } catch (Exception e) {
            log.debug("读取向量缓存失败: skillId={}, error={}", skillId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入或更新缓存。
     *
     * @param skillId     Skill ID
     * @param contentHash 搜索文本的 SHA-256 哈希
     * @param vector      向量数据
     */
    public void save(String skillId, String contentHash, float[] vector) {
        try {
            jdbcTemplate.update(UPSERT_SQL, skillId, contentHash, serializeVector(vector));
        } catch (Exception e) {
            log.warn("写入向量缓存失败: skillId={}, error={}", skillId, e.getMessage());
        }
    }

    /**
     * 删除指定 Skill 的缓存。
     *
     * @param skillId Skill ID
     */
    public void delete(String skillId) {
        try {
            jdbcTemplate.update(DELETE_SQL, skillId);
        } catch (Exception e) {
            log.debug("删除向量缓存失败: skillId={}, error={}", skillId, e.getMessage());
        }
    }

    private static byte[] serializeVector(float[] vector) {
        var buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    private static float[] deserializeVector(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
