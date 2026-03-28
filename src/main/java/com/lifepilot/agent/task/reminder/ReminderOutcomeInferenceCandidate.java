package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 待推断隐式结果的提醒候选。
 *
 * @param decisionId     决策 ID
 * @param notificationId 通知 ID
 * @param userId         用户 ID
 * @param topicKey       主题键
 * @param title          主题标题
 * @param candidateType  候选类型
 * @param decidedAt      决策时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderOutcomeInferenceCandidate(
        String decisionId,
        @Nullable String notificationId,
        String userId,
        String topicKey,
        String title,
        String candidateType,
        Instant decidedAt
) {
}
