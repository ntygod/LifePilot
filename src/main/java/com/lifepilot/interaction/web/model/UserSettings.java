package com.lifepilot.interaction.web.model;

/**
 * 用户设置。
 *
 * @param theme       主题（light / dark / system）
 * @param language    语言
 * @param llmProvider LLM Provider 标识
 * @author zsg
 * @since 2026-02-27
 */
public record UserSettings(
        String theme,
        String language,
        String llmProvider
) {}
