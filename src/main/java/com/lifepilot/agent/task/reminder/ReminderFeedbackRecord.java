package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒反馈记录。
 *
 * <p>V18 起新增 {@code insightEntityId}，用于把"此提醒无用"反馈溯源到生成该提醒时
 * 参考的 L3 {@code proactive_insight_*} 实体。写入时如无法确定 insight 关联则传
 * {@code null}；读取时 {@link ReminderFeedbackRepository#findInsightEntityIdsByNotification}
 * 会过滤掉 NULL 行。</p>
 *
 * @param id                反馈 ID
 * @param notificationId    通知 ID
 * @param userId            用户 ID
 * @param topicKey          主题键
 * @param feedbackType      反馈类型
 * @param comment           备注
 * @param insightEntityId   关联的 L3 proactive_insight 实体 ID（可为 null）
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
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
        @Nullable String insightEntityId,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 兼容不带 {@code insightEntityId} 的旧 8 参构造 —— 保留给历史调用点，
     * 新代码建议显式传 insightEntityId（不可知时传 {@code null}）。
     */
    public ReminderFeedbackRecord(
            String id,
            String notificationId,
            String userId,
            String topicKey,
            ReminderFeedbackType feedbackType,
            @Nullable String comment,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, notificationId, userId, topicKey, feedbackType, comment, null, createdAt, updatedAt);
    }
}
