package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 用户设置仓储。
 *
 * <p>当前仅负责维护真正的用户通用偏好。
 * 模型路由、精排配置等能力型设置已经迁移到独立表。</p>
 *
 * @author zsg
 * @since 2026-03-24
 */
@Repository
public class UserSettingsRepository {

    public static final String DEFAULT_SETTINGS_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(UserSettingsRepository.class);
    private static final Set<String> ALLOWED_JSON_COLUMNS = Set.of(
            "knowledge_config_json", "channel_config_json", "search_config_json"
    );

    private final JdbcTemplate jdbcTemplate;

    public UserSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前用户设置。
     *
     * @return 用户设置
     */
    public UserSettings getSettings() {
        return findById(DEFAULT_SETTINGS_ID)
                .orElseGet(this::createDefaultSettings);
    }

    /**
     * 保存用户设置。
     *
     * @param settings 用户设置
     */
    public void save(UserSettings settings) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO user_settings (
                    id, theme, language,
                    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
                    default_workspace,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    theme = excluded.theme,
                    language = excluded.language,
                    enable_streaming = excluded.enable_streaming,
                    enable_function_call = excluded.enable_function_call,
                    enable_knowledge_base = excluded.enable_knowledge_base,
                    enable_tool_call = excluded.enable_tool_call,
                    default_workspace = excluded.default_workspace,
                    updated_at = excluded.updated_at
                """,
                DEFAULT_SETTINGS_ID,
                settings.theme(),
                settings.language(),
                settings.enableStreaming() ? 1 : 0,
                settings.enableFunctionCall() ? 1 : 0,
                settings.enableKnowledgeBase() ? 1 : 0,
                settings.enableToolCall() ? 1 : 0,
                settings.defaultWorkspace(),
                now,
                now
        );
        log.debug("用户设置已保存: theme={}, language={}", settings.theme(), settings.language());
    }

    /**
     * 按 ID 查询用户设置。
     *
     * @param id 设置 ID
     * @return 用户设置
     */
    public Optional<UserSettings> findById(String id) {
        return jdbcTemplate.query(
                """
                SELECT theme, language, enable_streaming, enable_function_call,
                       enable_knowledge_base, enable_tool_call, default_workspace
                FROM user_settings WHERE id = ?
                """,
                (rs, rowNum) -> new UserSettings(
                        rs.getString("theme"),
                        rs.getString("language"),
                        rs.getInt("enable_streaming") == 1,
                        rs.getInt("enable_function_call") == 1,
                        rs.getInt("enable_knowledge_base") == 1,
                        rs.getInt("enable_tool_call") == 1,
                        rs.getString("default_workspace")
                ),
                id
        ).stream().findFirst();
    }

    /**
     * 读取知识库设置 JSON。
     *
     * @return JSON 字符串
     */
    public String getKnowledgeConfig() {
        return getJsonColumn("knowledge_config_json");
    }

    /**
     * 保存知识库设置 JSON。
     *
     * @param json JSON 字符串
     */
    public void saveKnowledgeConfig(String json) {
        saveJsonColumn("knowledge_config_json", json, "知识库配置已保存");
    }

    /**
     * 读取渠道配置 JSON。
     *
     * @return JSON 字符串
     */
    public String getChannelConfig() {
        return getJsonColumn("channel_config_json");
    }

    /**
     * 保存渠道配置 JSON。
     *
     * @param json JSON 字符串
     */
    public void saveChannelConfig(String json) {
        saveJsonColumn("channel_config_json", json, "渠道配置已保存");
    }

    /**
     * 读取联网搜索配置 JSON。
     *
     * @return JSON 字符串
     */
    public String getSearchConfig() {
        return getJsonColumn("search_config_json");
    }

    /**
     * 保存联网搜索配置 JSON。
     *
     * @param json JSON 字符串
     */
    public void saveSearchConfig(String json) {
        saveJsonColumn("search_config_json", json, "联网搜索配置已保存");
    }

    private UserSettings createDefaultSettings() {
        UserSettings defaultSettings = new UserSettings("system", "zh-CN", true, true, true, true, null);
        save(defaultSettings);
        return defaultSettings;
    }

    private String getJsonColumn(String columnName) {
        validateColumnName(columnName);
        getSettings();
        var results = jdbcTemplate.query(
                "SELECT " + columnName + " FROM user_settings WHERE id = ?",
                (rs, rowNum) -> rs.getString(columnName),
                DEFAULT_SETTINGS_ID
        );
        String json = results.stream().findFirst().orElse(null);
        if (json == null || json.isBlank()) {
            return "{}";
        }
        return json;
    }

    private void saveJsonColumn(String columnName, String json, String logMessage) {
        validateColumnName(columnName);
        getSettings();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE user_settings SET " + columnName + " = ?, updated_at = ? WHERE id = ?",
                json,
                now,
                DEFAULT_SETTINGS_ID
        );
        log.debug(logMessage);
    }

    private static void validateColumnName(String columnName) {
        if (!ALLOWED_JSON_COLUMNS.contains(columnName)) {
            throw new IllegalArgumentException("不允许的列名: " + columnName);
        }
    }
}
