package com.lifepilot.interaction.web.model;

/**
 * 消息反馈请求。
 *
 * @param type     反馈类型（'like' 或 'dislike'）
 * @param feedback 点踩时的反馈内容（可选，仅在 type='dislike' 时使用）
 * @author zsg
 * @since 2026-02-28
 */
public record FeedbackRequest(
        String type,
        String feedback
) {}
