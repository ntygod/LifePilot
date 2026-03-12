package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 发送消息请求体。
 *
 * @param content           消息文本内容
 * @param sessionId         会话 ID（可为 null，新会话时自动创建）
 * @param attachmentIds     本轮消息关联的附件 ID 列表
 * @param preferredProvider 会话级偏好 LLM Provider（可为 null，使用默认路由）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatRequest(
        String content,
        @Nullable String sessionId,
        @Nullable List<String> attachmentIds,
        @Nullable String preferredProvider
) {}
