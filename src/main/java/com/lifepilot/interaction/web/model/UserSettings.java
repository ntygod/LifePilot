package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * 用户设置。
 *
 * @param theme             主题（light / dark / system）
 * @param language          语言
 * @param llmProvider       全局默认 LLM Provider 标识（可空，空时走自动路由）
 * @param sceneProviders    按场景指定默认 Provider（scene -> providerId）
 * @param enableStreaming  启用流式响应
 * @param enableFunctionCall 启用函数调用
 * @param enableKnowledgeBase 启用知识库检索
 * @param enableToolCall   启用工具调用
 * @author zsg
 * @since 2026-02-27
 */
public record UserSettings(
        String theme,
        String language,
        String llmProvider,
        Map<String, String> sceneProviders,
        Boolean enableStreaming,
        Boolean enableFunctionCall,
        Boolean enableKnowledgeBase,
        Boolean enableToolCall
) {
    /**
     * 紧凑构造器：为 null 的布尔值设置默认值 true。
     */
    public UserSettings {
        sceneProviders = sceneProviders != null
                ? sceneProviders.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right
                ))
                : Map.of();
        if (enableStreaming == null) {
            enableStreaming = true;
        }
        if (enableFunctionCall == null) {
            enableFunctionCall = true;
        }
        if (enableKnowledgeBase == null) {
            enableKnowledgeBase = true;
        }
        if (enableToolCall == null) {
            enableToolCall = true;
        }
    }
}
