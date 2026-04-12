package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.lang.Nullable;

/**
 * 用户通用设置。
 *
 * <p>这里只保留真正属于用户偏好的基础设置。
 * 模型服务与路由配置已经迁移到独立的 model routing 表中，不再经过 user_settings。</p>
 *
 * @param theme               主题
 * @param language            语言
 * @param enableStreaming     是否启用流式输出
 * @param enableFunctionCall  是否启用函数调用
 * @param enableKnowledgeBase 是否启用知识库
 * @param enableToolCall      是否启用工具调用
 * @param defaultWorkspace    默认工作目录（null 表示使用系统默认 ~/.zhiwei/workspace/）
 * @author zsg
 * @since 2026-03-24
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSettings(
        String theme,
        String language,
        Boolean enableStreaming,
        Boolean enableFunctionCall,
        Boolean enableKnowledgeBase,
        Boolean enableToolCall,
        @Nullable String defaultWorkspace
) {

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
