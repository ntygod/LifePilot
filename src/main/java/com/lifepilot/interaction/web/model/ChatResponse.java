package com.lifepilot.interaction.web.model;

import com.lifepilot.interaction.model.TokenUsage;
import org.springframework.lang.Nullable;

/**
 * 非流式消息响应体。
 *
 * @param messageId  消息 ID
 * @param content    文本内容
 * @param a2ui       A2UI 组件树（可为 null）
 * @param tokenUsage Token 消耗统计（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatResponse(
        String messageId,
        String content,
        @Nullable A2uiComponentTree a2ui,
        @Nullable TokenUsage tokenUsage
) {}
