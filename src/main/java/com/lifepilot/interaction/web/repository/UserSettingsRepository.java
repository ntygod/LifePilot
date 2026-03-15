package com.lifepilot.interaction.web.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 用户设置数据访问层。
 *
 * <p>基于 JdbcTemplate 操作 user_settings 表，提供设置的 CRUD 操作。
 * 当前使用固定 ID 'default' 存储单用户设置，后续可扩展为多用户支持。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Repository
public class UserSettingsRepository {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsRepository.class);

    /** 默认设置 ID（单用户模式） */
    private static final String DEFAULT_SETTINGS_ID = "default";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UserSettingsRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前用户设置。
     *
     * <p>如果数据库中没有设置，返回默认设置。
     *
     * @return 用户设置
     */
    public UserSettings getSettings() {
        return findById(DEFAULT_SETTINGS_ID)
                .orElseGet(() -> {
                    log.debug("未找到用户设置，返回默认设置");
                    return createDefaultSettings();
                });
    }

    /**
     * 保存用户设置（upsert 语义）。
     *
     * @param settings 用户设置
     */
    public void save(UserSettings settings) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO user_settings (
                    id, theme, language, llm_provider, scene_providers,
                    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    theme = excluded.theme,
                    language = excluded.language,
                    llm_provider = excluded.llm_provider,
                    scene_providers = excluded.scene_providers,
                    enable_streaming = excluded.enable_streaming,
                    enable_function_call = excluded.enable_function_call,
                    enable_knowledge_base = excluded.enable_knowledge_base,
                    enable_tool_call = excluded.enable_tool_call,
                    updated_at = excluded.updated_at
                """,
                DEFAULT_SETTINGS_ID,
                settings.theme(),
                settings.language(),
                settings.llmProvider(),
                serializeSceneProviders(settings.sceneProviders()),
                settings.enableStreaming() ? 1 : 0,
                settings.enableFunctionCall() ? 1 : 0,
                settings.enableKnowledgeBase() ? 1 : 0,
                settings.enableToolCall() ? 1 : 0,
                now,
                now);
        log.debug("用户设置已保存: theme={}, language={}, llmProvider={}",
                settings.theme(), settings.language(), settings.llmProvider());
    }

    /**
     * 根据 ID 查找用户设置。
     *
     * @param id 设置 ID
     * @return 用户设置 Optional
     */
    public Optional<UserSettings> findById(String id) {
        return jdbcTemplate.query(
                "SELECT * FROM user_settings WHERE id = ?",
                this::mapRow, id)
                .stream()
                .findFirst();
    }

    /**
     * 创建并保存默认设置。
     *
     * @return 默认设置
     */
    private UserSettings createDefaultSettings() {
        UserSettings defaultSettings = new UserSettings(
                "system", "zh-CN", "", java.util.Map.of(), true, true, true, true);
        save(defaultSettings);
        return defaultSettings;
    }

    /**
     * 将 ResultSet 行映射为 UserSettings record。
     */
    private UserSettings mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserSettings(
                rs.getString("theme"),
                rs.getString("language"),
                rs.getString("llm_provider"),
                deserializeSceneProviders(rs.getString("scene_providers")),
                rs.getInt("enable_streaming") == 1,
                rs.getInt("enable_function_call") == 1,
                rs.getInt("enable_knowledge_base") == 1,
                rs.getInt("enable_tool_call") == 1
        );
    }

    private String serializeSceneProviders(java.util.Map<String, String> sceneProviders) {
        try {
            return objectMapper.writeValueAsString(sceneProviders != null ? sceneProviders : java.util.Map.of());
        } catch (Exception e) {
            log.warn("场景 Provider 配置序列化失败，使用空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 读取 Reranker 配置 JSON。
     *
     * @return reranker_config_json 列的值，不存在或为空时返回 "{}"
     */
    public String getRerankerConfig() {
        var results = jdbcTemplate.query(
                "SELECT reranker_config_json FROM user_settings WHERE id = ?",
                (rs, rowNum) -> rs.getString("reranker_config_json"),
                DEFAULT_SETTINGS_ID);
        String json = results.stream().findFirst().orElse(null);
        if (json == null || json.isBlank()) {
            return "{}";
        }
        return json;
    }

    /**
     * 保存 Reranker 配置 JSON。
     *
     * <p>如果 user_settings 行不存在，先创建默认行再更新。
     *
     * @param json Reranker 配置 JSON 字符串
     */
    public void saveRerankerConfig(String json) {
        // 确保 default 行存在
        getSettings();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE user_settings SET reranker_config_json = ?, updated_at = ? WHERE id = ?",
                json, now, DEFAULT_SETTINGS_ID);
        log.debug("Reranker 配置已保存: json={}", json);
    }

    private java.util.Map<String, String> deserializeSceneProviders(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            java.util.Map<String, String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? java.util.Map.copyOf(result) : java.util.Map.of();
        } catch (Exception e) {
            log.warn("场景 Provider 配置反序列化失败，使用空对象: error={}", e.getMessage());
            return java.util.Map.of();
        }
    }
}
