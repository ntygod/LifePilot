package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒主题偏好记录。
 *
 * @param userId    用户 ID
 * @param topicKey  主题键
 * @param muted     是否静默
 * @param mutedAt   静默时间
 * @param updatedAt 更新时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderTopicPreferenceRecord(
        String userId,
        String topicKey,
        boolean muted,
        @Nullable Instant mutedAt,
        Instant updatedAt
) {
}
