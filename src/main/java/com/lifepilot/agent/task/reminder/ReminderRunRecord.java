package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒执行轮次记录。
 *
 * @param id                轮次 ID
 * @param userId            用户 ID
 * @param startedAt         开始时间
 * @param finishedAt        结束时间
 * @param topicsCollected   采集主题数
 * @param decisionsEvaluated 决策数
 * @param remindersSent     实际发送数
 * @param policyVersionId   策略版本 ID
 * @param policyVersion     策略版本号
 * @param contextJson       运行上下文快照 JSON
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderRunRecord(
        String id,
        String userId,
        Instant startedAt,
        @Nullable Instant finishedAt,
        int topicsCollected,
        int decisionsEvaluated,
        int remindersSent,
        @Nullable String policyVersionId,
        @Nullable Integer policyVersion,
        @Nullable String contextJson,
        Instant createdAt,
        Instant updatedAt
) {

    public ReminderRunRecord(String id,
                             String userId,
                             Instant startedAt,
                             @Nullable Instant finishedAt,
                             int topicsCollected,
                             int decisionsEvaluated,
                             int remindersSent,
                             @Nullable String contextJson,
                             Instant createdAt,
                             Instant updatedAt) {
        this(id, userId, startedAt, finishedAt, topicsCollected, decisionsEvaluated, remindersSent,
                null, null, contextJson, createdAt, updatedAt);
    }
}
