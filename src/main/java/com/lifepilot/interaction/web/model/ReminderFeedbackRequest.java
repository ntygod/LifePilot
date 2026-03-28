package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 主动提醒反馈请求。
 *
 * @param feedbackType 反馈类型
 * @param comment      反馈备注
 * @param muteTopic    是否同步静默该主题
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderFeedbackRequest(
        String feedbackType,
        @Nullable String comment,
        @Nullable Boolean muteTopic
) {
}
