package com.lifepilot.modelservice.repository;

import com.lifepilot.modelservice.model.RerankExecutionMode;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 精排设置仓储。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Repository
public class RerankSettingsRepository {

    public static final String DEFAULT_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(RerankSettingsRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public RerankSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询默认精排设置。
     *
     * @return 设置
     */
    public Optional<RerankSettingsEntity> findDefault() {
        return findById(DEFAULT_ID);
    }

    /**
     * 按 ID 查询精排设置。
     *
     * @param id 设置 ID
     * @return 设置
     */
    public Optional<RerankSettingsEntity> findById(String id) {
        return jdbcTemplate.query(
                """
                SELECT id, enabled, mode, native_service_id, llm_service_id,
                       knowledge_top_k, memory_enabled, memory_top_k
                FROM rerank_settings WHERE id = ?
                """,
                (rs, rowNum) -> new RerankSettingsEntity(
                        rs.getString("id"),
                        rs.getInt("enabled") == 1,
                        RerankExecutionMode.valueOf(rs.getString("mode")),
                        rs.getString("native_service_id"),
                        rs.getString("llm_service_id"),
                        rs.getInt("knowledge_top_k"),
                        rs.getInt("memory_enabled") == 1,
                        rs.getInt("memory_top_k")),
                id
        ).stream().findFirst();
    }

    /**
     * 保存精排设置。
     *
     * @param entity 设置实体
     */
    public void save(RerankSettingsEntity entity) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO rerank_settings (
                    id, enabled, mode, native_service_id, llm_service_id,
                    knowledge_top_k, memory_enabled, memory_top_k, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    enabled = excluded.enabled,
                    mode = excluded.mode,
                    native_service_id = excluded.native_service_id,
                    llm_service_id = excluded.llm_service_id,
                    knowledge_top_k = excluded.knowledge_top_k,
                    memory_enabled = excluded.memory_enabled,
                    memory_top_k = excluded.memory_top_k,
                    updated_at = excluded.updated_at
                """,
                entity.id(),
                entity.enabled() ? 1 : 0,
                entity.mode().name(),
                entity.nativeServiceId(),
                entity.llmServiceId(),
                entity.knowledgeTopK(),
                entity.memoryEnabled() ? 1 : 0,
                entity.memoryTopK(),
                now,
                now);
        log.debug("精排设置已保存: id={}, mode={}", entity.id(), entity.mode());
    }
}
