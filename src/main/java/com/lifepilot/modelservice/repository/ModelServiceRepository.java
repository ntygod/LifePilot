package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型服务仓储。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Repository
public class ModelServiceRepository {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelServiceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询全部模型服务。
     *
     * @return 模型服务列表
     */
    public List<ModelServiceEntity> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, kind, provider_type, api_url, api_key, model_name,
                       timeout_seconds, priority, enabled, supported_scenes_json,
                       generation_capabilities_json, metadata_json, display_name, description
                FROM model_services ORDER BY kind ASC, priority ASC, id ASC
                """,
                this::mapRow);
    }

    /**
     * 按类型查询模型服务。
     *
     * @param kind 服务类型
     * @return 匹配列表
     */
    public List<ModelServiceEntity> findByKind(ModelServiceKind kind) {
        return jdbcTemplate.query(
                """
                SELECT id, kind, provider_type, api_url, api_key, model_name,
                       timeout_seconds, priority, enabled, supported_scenes_json,
                       generation_capabilities_json, metadata_json, display_name, description
                FROM model_services WHERE kind = ? ORDER BY priority ASC, id ASC
                """,
                this::mapRow,
                kind.name());
    }

    /**
     * 查询指定类型且已启用的模型服务。
     *
     * @param kind 服务类型
     * @return 已启用服务列表
     */
    public List<ModelServiceEntity> findEnabledByKind(ModelServiceKind kind) {
        return jdbcTemplate.query(
                """
                SELECT id, kind, provider_type, api_url, api_key, model_name,
                       timeout_seconds, priority, enabled, supported_scenes_json,
                       generation_capabilities_json, metadata_json, display_name, description
                FROM model_services WHERE kind = ? AND enabled = 1 ORDER BY priority ASC, id ASC
                """,
                this::mapRow,
                kind.name());
    }

    /**
     * 按 ID 查询模型服务。
     *
     * @param id 服务 ID
     * @return 服务实体
     */
    public Optional<ModelServiceEntity> findById(String id) {
        return jdbcTemplate.query(
                """
                SELECT id, kind, provider_type, api_url, api_key, model_name,
                       timeout_seconds, priority, enabled, supported_scenes_json,
                       generation_capabilities_json, metadata_json, display_name, description
                FROM model_services WHERE id = ?
                """,
                this::mapRow,
                id).stream().findFirst();
    }

    /**
     * 保存模型服务。
     *
     * @param entity 服务实体
     */
    public void save(ModelServiceEntity entity) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO model_services (
                    id, kind, provider_type, api_url, api_key, model_name,
                    timeout_seconds, priority, enabled, supported_scenes_json,
                    generation_capabilities_json, metadata_json,
                    display_name, description, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    kind = excluded.kind,
                    provider_type = excluded.provider_type,
                    api_url = excluded.api_url,
                    api_key = excluded.api_key,
                    model_name = excluded.model_name,
                    timeout_seconds = excluded.timeout_seconds,
                    priority = excluded.priority,
                    enabled = excluded.enabled,
                    supported_scenes_json = excluded.supported_scenes_json,
                    generation_capabilities_json = excluded.generation_capabilities_json,
                    metadata_json = excluded.metadata_json,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    updated_at = excluded.updated_at
                """,
                entity.id(),
                entity.kind().name(),
                entity.providerType().name(),
                entity.apiUrl(),
                entity.apiKey(),
                entity.modelName(),
                entity.timeoutSeconds(),
                entity.priority(),
                entity.enabled() ? 1 : 0,
                writeJson(entity.supportedScenes()),
                writeJson(entity.generationCapabilities().stream().map(Enum::name).toList()),
                writeJson(entity.metadata()),
                entity.displayName(),
                entity.description(),
                now,
                now);
        log.debug("模型服务已保存: id={}, kind={}", entity.id(), entity.kind());
    }

    /**
     * 删除模型服务。
     *
     * @param id 服务 ID
     * @return 删除行数
     */
    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM model_services WHERE id = ?", id);
    }

    private ModelServiceEntity mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelServiceEntity(
                rs.getString("id"),
                ModelServiceKind.valueOf(rs.getString("kind")),
                ProviderType.valueOf(rs.getString("provider_type")),
                rs.getString("api_url"),
                rs.getString("api_key"),
                rs.getString("model_name"),
                rs.getInt("timeout_seconds"),
                rs.getInt("priority"),
                rs.getInt("enabled") == 1,
                readStringList(rs.getString("supported_scenes_json")),
                readCapabilitySet(rs.getString("generation_capabilities_json")),
                readMetadata(rs.getString("metadata_json")),
                rs.getString("display_name"),
                rs.getString("description"));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析模型服务场景列表失败: " + e.getMessage(), e);
        }
    }

    private Set<GenerationCapability> readCapabilitySet(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> names = objectMapper.readValue(json, new TypeReference<>() {});
            return names == null
                    ? Set.of()
                    : names.stream().map(GenerationCapability::valueOf).collect(Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            throw new IllegalArgumentException("解析模型服务能力列表失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("解析模型服务元数据失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("序列化模型服务字段失败: " + e.getMessage(), e);
        }
    }
}
