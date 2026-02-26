package com.lifepilot.interaction.web.controller;

import java.util.concurrent.atomic.AtomicReference;

import com.lifepilot.interaction.web.model.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户设置 REST 端点，提供设置的读取和更新功能。
 *
 * <p>当前使用内存 {@link AtomicReference} 存储设置，后续将替换为数据库持久化。
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    /** 默认用户设置 */
    private static final UserSettings DEFAULT_SETTINGS =
            new UserSettings("system", "zh-CN", "ollama-qwen2.5");

    /** 内存设置存储（线程安全） */
    private final AtomicReference<UserSettings> settingsRef = new AtomicReference<>(DEFAULT_SETTINGS);

    /**
     * 获取当前用户设置。
     *
     * @return 当前用户设置
     */
    @GetMapping
    public ResponseEntity<UserSettings> getSettings() {
        log.debug("获取用户设置");
        return ResponseEntity.ok(settingsRef.get());
    }

    /**
     * 更新用户设置。
     *
     * @param settings 新的用户设置
     * @return 更新后的用户设置
     * @throws IllegalArgumentException 如果设置参数无效
     */
    @PutMapping
    public ResponseEntity<UserSettings> updateSettings(@RequestBody UserSettings settings) {
        if (settings.theme() == null || settings.theme().isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (settings.language() == null || settings.language().isBlank()) {
            throw new IllegalArgumentException("语言不能为空");
        }
        if (settings.llmProvider() == null || settings.llmProvider().isBlank()) {
            throw new IllegalArgumentException("LLM Provider 不能为空");
        }

        log.debug("更新用户设置: theme={}, language={}, llmProvider={}",
                settings.theme(), settings.language(), settings.llmProvider());
        settingsRef.set(settings);
        return ResponseEntity.ok(settingsRef.get());
    }
}
