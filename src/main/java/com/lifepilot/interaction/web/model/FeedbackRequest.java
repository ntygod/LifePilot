package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 消息反馈请求。
 *
 * @param type     反馈类型（'like' 或 'dislike'）
 * @param feedback 反馈内容（可选）
 * @author zsg
 * @since 2026-02-28
 */
public record FeedbackRequest(
        String type,
        @Nullable String feedback
) {}
