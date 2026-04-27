package com.lifepilot.modelservice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.ModelServiceTemplate;
import com.lifepilot.modelservice.model.ModelServiceTemplateModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型服务模板仓储。
 *
 * @author zsg
 * @since 2026-03-30
 */
@Repository
public class ModelServiceTemplateRepository {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelServiceTemplateRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModelServiceTemplateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ModelServiceTemplate> findAll() {
        Map<String, ModelServiceTemplateRow> templates = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT vendor_key,
                       display_name,
                       provider_type,
                       description,
                       default_api_url,
                       supported_kinds_json,
                       default_timeout_seconds,
                       default_generation_capabilities_json,
                       default_generation_scenes_json,
                       default_supports_streaming,
                       default_max_context_window
                FROM model_service_vendor_templates
                ORDER BY sort_order ASC, vendor_key ASC
                """, rs -> {
            ModelServiceTemplateRow row = mapTemplateRow(rs);
            templates.put(row.vendorKey(), row);
        });

        Map<String, List<ModelServiceTemplateModel>> modelOptionsByVendor = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT vendor_key,
                       kind,
                       model_value,
                       label,
                       recommended,
                       capabilities_json,
                       scenes_json,
                       supports_streaming,
                       max_context_window,
                       embedding_dimension,
                       sort_order
                FROM model_service_model_presets
                ORDER BY vendor_key ASC, kind ASC, sort_order ASC, model_value ASC
                """, rs -> {
            ModelServiceTemplateModel model = mapModelRow(rs);
            modelOptionsByVendor.computeIfAbsent(model.vendorKey(), ignored -> new java.util.ArrayList<>()).add(model);
        });

        return templates.values().stream()
                .map(row -> new ModelServiceTemplate(
                        row.vendorKey(),
                        row.displayName(),
                        row.providerType(),
                        row.description(),
                        row.defaultApiUrl(),
                        row.supportedKinds(),
                        row.defaultTimeoutSeconds(),
                        row.defaultCapabilities(),
                        row.defaultScenes(),
                        row.defaultSupportsStreaming(),
                        row.defaultMaxContextWindow(),
                        modelOptionsByVendor.getOrDefault(row.vendorKey(), List.of()).stream()
                                .sorted(Comparator.comparingInt(ModelServiceTemplateModel::sortOrder))
                                .toList()
                ))
                .toList();
    }

    public boolean existsByVendorKey(String vendorKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_service_vendor_templates WHERE vendor_key = ?",
                Integer.class,
                vendorKey);
        return count != null && count > 0;
    }

    private ModelServiceTemplateRow mapTemplateRow(ResultSet rs) throws SQLException {
        String providerTypeRaw = rs.getString("provider_type");
        String providerType = providerTypeRaw != null && !providerTypeRaw.isBlank()
                ? providerTypeRaw.trim() : "OPENAI_COMPATIBLE";
        return new ModelServiceTemplateRow(
                rs.getString("vendor_key"),
                rs.getString("display_name"),
                providerType,
                rs.getString("description"),
                rs.getString("default_api_url"),
                readKinds(rs.getString("supported_kinds_json")),
                rs.getInt("default_timeout_seconds"),
                readStringList(rs.getString("default_generation_capabilities_json")),
                readStringList(rs.getString("default_generation_scenes_json")),
                rs.getInt("default_supports_streaming") == 1,
                integerOrNull(rs, "default_max_context_window")
        );
    }

    private ModelServiceTemplateModel mapModelRow(ResultSet rs) throws SQLException {
        return new ModelServiceTemplateModel(
                rs.getString("vendor_key"),
                safeValueOf(ModelServiceKind.class, rs.getString("kind"), ModelServiceKind.GENERATION),
                rs.getString("model_value"),
                rs.getString("label"),
                rs.getInt("recommended") == 1,
                readStringList(rs.getString("capabilities_json")),
                readStringList(rs.getString("scenes_json")),
                rs.getInt("supports_streaming") == 1,
                integerOrNull(rs, "max_context_window"),
                integerOrNull(rs, "embedding_dimension"),
                rs.getInt("sort_order")
        );
    }

    private List<ModelServiceKind> readKinds(String json) {
        return readStringList(json).stream()
                .map(value -> safeValueOf(ModelServiceKind.class, value, null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : List.of();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("解析模板列表失败: " + e.getMessage(), e);
        }
    }

    private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static <T extends Enum<T>> T safeValueOf(Class<T> enumType, String value, @Nullable T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException e) {
            log.warn("未识别的枚举值，使用默认值: type={}, value={}, fallback={}", enumType.getSimpleName(), value, fallback);
            return fallback;
        }
    }

    private record ModelServiceTemplateRow(
            String vendorKey,
            String displayName,
            String providerType,
            String description,
            String defaultApiUrl,
            List<ModelServiceKind> supportedKinds,
            int defaultTimeoutSeconds,
            List<String> defaultCapabilities,
            List<String> defaultScenes,
            boolean defaultSupportsStreaming,
            Integer defaultMaxContextWindow
    ) {
    }
}
