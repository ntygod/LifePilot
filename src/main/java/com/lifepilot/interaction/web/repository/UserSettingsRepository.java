package com.lifepilot.interaction.web.repository;

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

    public UserSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
                    id, theme, language, llm_provider,
                    enable_streaming, enable_function_call, enable_knowledge_base, enable_tool_call,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    theme = excluded.theme,
                    language = excluded.language,
                    llm_provider = excluded.llm_provider,
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
                "system", "zh-CN", "ollama-qwen2.5", true, true, true, true);
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
                rs.getInt("enable_streaming") == 1,
                rs.getInt("enable_function_call") == 1,
                rs.getInt("enable_knowledge_base") == 1,
                rs.getInt("enable_tool_call") == 1
        );
    }
}
