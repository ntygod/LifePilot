package com.lifepilot.interaction.web.model;

/**
 * 用户设置。
 *
 * @param theme             主题（light / dark / system）
 * @param language          语言
 * @param llmProvider      LLM Provider 标识
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
        Boolean enableStreaming,
        Boolean enableFunctionCall,
        Boolean enableKnowledgeBase,
        Boolean enableToolCall
) {
    /**
     * 紧凑构造器：为 null 的布尔值设置默认值 true。
     */
    public UserSettings {
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
