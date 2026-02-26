package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 发送消息请求体。
 *
 * @param content   消息文本内容
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatRequest(
        String content,
        @Nullable String sessionId
) {}
