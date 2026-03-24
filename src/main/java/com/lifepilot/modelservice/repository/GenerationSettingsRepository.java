package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 生成设置仓储。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Repository
public class GenerationSettingsRepository {

    public static final String DEFAULT_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(GenerationSettingsRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public GenerationSettingsRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询默认生成设置。
     *
     * @return 默认设置
     */
    public Optional<GenerationSettingsEntity> findDefault() {
        return findById(DEFAULT_ID);
    }

    /**
     * 按 ID 查询设置。
     *
     * @param id 设置 ID
     * @return 设置
     */
    public Optional<GenerationSettingsEntity> findById(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM generation_settings WHERE id = ?",
                (rs, rowNum) -> new GenerationSettingsEntity(
                        rs.getString("id"),
                        rs.getString("default_service_id"),
                        readBindings(rs.getString("scene_service_bindings_json"))),
                id).stream().findFirst();
    }

    /**
     * 保存生成设置。
     *
     * @param entity 设置实体
     */
    public void save(GenerationSettingsEntity entity) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO generation_settings (
                    id, default_service_id, scene_service_bindings_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    default_service_id = excluded.default_service_id,
                    scene_service_bindings_json = excluded.scene_service_bindings_json,
                    updated_at = excluded.updated_at
                """,
                entity.id(),
                entity.defaultServiceId(),
                writeJson(entity.sceneServiceBindings()),
                now,
                now);
        log.debug("生成设置已保存: id={}, defaultServiceId={}", entity.id(), entity.defaultServiceId());
    }

    private Map<String, String> readBindings(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析生成设置场景绑定失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化生成设置失败: " + e.getMessage(), e);
        }
    }
}
