package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 通知反馈视图。
 *
 * @param notificationId 通知 ID
 * @param topicKey       主题键
 * @param feedbackType   反馈类型
 * @param comment        备注
 * @param feedbackAt     反馈时间
 * @param topicMuted     主题是否静默
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderNotificationFeedbackView(
        String notificationId,
        String topicKey,
        ReminderFeedbackType feedbackType,
        @Nullable String comment,
        Instant feedbackAt,
        boolean topicMuted
) {
}
