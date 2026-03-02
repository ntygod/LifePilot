package com.lifepilot.llm.repository;

import com.lifepilot.llm.config.LlmProviderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LLM Provider 数据访问层。
 *
 * <p>基于 JdbcTemplate 操作 llm_providers 表，提供 Provider 配置的 CRUD 操作。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Repository
public class LlmProviderRepository {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public LlmProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查找所有 Provider（包括已禁用）。
     *
     * @return Provider 列表
     */
    public List<LlmProviderEntity> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM llm_providers ORDER BY priority ASC, id ASC",
                this::mapRow);
    }

    /**
     * 查找所有已启用的 Provider。
     *
     * @return 已启用的 Provider 列表
     */
    public List<LlmProviderEntity> findAllEnabled() {
        return jdbcTemplate.query(
                "SELECT * FROM llm_providers WHERE enabled = 1 ORDER BY priority ASC, id ASC",
                this::mapRow);
    }

    /**
     * 根据 ID 查找 Provider。
     *
     * @param id Provider ID
     * @return Provider Optional
     */
    public Optional<LlmProviderEntity> findById(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM llm_providers WHERE id = ?",
                this::mapRow, id)
                .stream()
                .findFirst();
    }

    /**
     * 查找所有预设置的 Provider。
     *
     * @return 预设置 Provider 列表
     */
    public List<LlmProviderEntity> findPresets() {
        return jdbcTemplate.query(
                "SELECT * FROM llm_providers WHERE is_preset = 1 ORDER BY type ASC, id ASC",
                this::mapRow);
    }

    /**
     * 保存 Provider（insert 或 update）。
     *
     * @param entity Provider 实体
     */
    public void save(LlmProviderEntity entity) {
        String now = Instant.now().toString();
        Object[] params = entity.toRowParams();
        jdbcTemplate.update("""
                INSERT INTO llm_providers (
                    id, type, api_url, api_key, model_name, timeout_seconds, priority,
                    scenes, capabilities, enabled, cost_per_input_token, cost_per_output_token,
                    max_context_window, embedding_dimension, supports_streaming, is_preset,
                    display_name, description, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    type = excluded.type,
                    api_url = excluded.api_url,
                    api_key = excluded.api_key,
                    model_name = excluded.model_name,
                    timeout_seconds = excluded.timeout_seconds,
                    priority = excluded.priority,
                    scenes = excluded.scenes,
                    capabilities = excluded.capabilities,
                    enabled = excluded.enabled,
                    cost_per_input_token = excluded.cost_per_input_token,
                    cost_per_output_token = excluded.cost_per_output_token,
                    max_context_window = excluded.max_context_window,
                    embedding_dimension = excluded.embedding_dimension,
                    supports_streaming = excluded.supports_streaming,
                    is_preset = excluded.is_preset,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    updated_at = excluded.updated_at
                """,
                params[0], params[1], params[2], params[3], params[4], params[5], params[6],
                params[7], params[8], params[9], params[10], params[11], params[12], params[13],
                params[14], params[15], params[16], params[17], now, now);
        log.debug("Provider 已保存: id={}, type={}", entity.id(), entity.type());
    }

    /**
     * 删除 Provider。
     *
     * @param id Provider ID
     * @return 删除的行数
     */
    public int deleteById(String id) {
        int rows = jdbcTemplate.update("DELETE FROM llm_providers WHERE id = ?", id);
        if (rows > 0) {
            log.debug("Provider 已删除: id={}", id);
        }
        return rows;
    }

    /**
     * 检查 Provider 是否存在。
     *
     * @param id Provider ID
     * @return 存在返回 true
     */
    public boolean existsById(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_providers WHERE id = ?",
                Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 将 ResultSet 行映射为 LlmProviderEntity。
     */
    private LlmProviderEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return LlmProviderEntity.fromRow(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("api_url"),
                rs.getString("api_key"),
                rs.getString("model_name"),
                rs.getInt("timeout_seconds"),
                rs.getInt("priority"),
                rs.getString("scenes"),
                rs.getString("capabilities"),
                rs.getInt("enabled") == 1,
                rs.getInt("cost_per_input_token"),
                rs.getInt("cost_per_output_token"),
                rs.getInt("max_context_window"),
                rs.getObject("embedding_dimension") != null
                        ? rs.getInt("embedding_dimension") : null,
                rs.getInt("supports_streaming") == 1,
                rs.getInt("is_preset") == 1,
                rs.getString("display_name"),
                rs.getString("description")
        );
    }
}
