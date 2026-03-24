package com.lifepilot.modelservice.repository;

import com.lifepilot.modelservice.model.EmbeddingSettingsEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 向量化设置仓储。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Repository
public class EmbeddingSettingsRepository {

    public static final String DEFAULT_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSettingsRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询默认向量化设置。
     *
     * @return 设置
     */
    public Optional<EmbeddingSettingsEntity> findDefault() {
        return findById(DEFAULT_ID);
    }

    /**
     * 按 ID 查询向量化设置。
     *
     * @param id 设置 ID
     * @return 设置
     */
    public Optional<EmbeddingSettingsEntity> findById(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM embedding_settings WHERE id = ?",
                (rs, rowNum) -> new EmbeddingSettingsEntity(
                        rs.getString("id"),
                        rs.getString("default_service_id"),
                        rs.getString("knowledge_base_service_id"),
                        rs.getString("memory_service_id")),
                id).stream().findFirst();
    }

    /**
     * 保存向量化设置。
     *
     * @param entity 设置实体
     */
    public void save(EmbeddingSettingsEntity entity) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO embedding_settings (
                    id, default_service_id, knowledge_base_service_id, memory_service_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    default_service_id = excluded.default_service_id,
                    knowledge_base_service_id = excluded.knowledge_base_service_id,
                    memory_service_id = excluded.memory_service_id,
                    updated_at = excluded.updated_at
                """,
                entity.id(),
                entity.defaultServiceId(),
                entity.knowledgeBaseServiceId(),
                entity.memoryServiceId(),
                now,
                now);
        log.debug("向量化设置已保存: id={}, defaultServiceId={}", entity.id(), entity.defaultServiceId());
    }
}
