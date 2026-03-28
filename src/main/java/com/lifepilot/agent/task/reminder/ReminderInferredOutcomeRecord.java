package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 提醒隐式结果记录。
 *
 * @param id              记录 ID
 * @param decisionId      决策 ID
 * @param notificationId  通知 ID
 * @param userId          用户 ID
 * @param topicKey        主题键
 * @param outcomeType     结果类型
 * @param evidenceSource  证据来源
 * @param confidenceScore 置信度
 * @param attributionScore 提醒促成完成的去因分
 * @param evidenceJson    证据快照
 * @param inferredAt      推断时间
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderInferredOutcomeRecord(
        String id,
        String decisionId,
        @Nullable String notificationId,
        String userId,
        String topicKey,
        ReminderOutcomeType outcomeType,
        ReminderOutcomeEvidenceSource evidenceSource,
        float confidenceScore,
        float attributionScore,
        @Nullable String evidenceJson,
        Instant inferredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
