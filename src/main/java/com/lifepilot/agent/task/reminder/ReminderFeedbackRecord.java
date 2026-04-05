package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒反馈记录。
 *
 * @param id             反馈 ID
 * @param notificationId 通知 ID
 * @param userId         用户 ID
 * @param topicKey       主题键
 * @param feedbackType   反馈类型
 * @param comment        备注
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderFeedbackRecord(
        String id,
        String notificationId,
        String userId,
        String topicKey,
        ReminderFeedbackType feedbackType,
        @Nullable String comment,
        Instant createdAt,
        Instant updatedAt
) {
}
