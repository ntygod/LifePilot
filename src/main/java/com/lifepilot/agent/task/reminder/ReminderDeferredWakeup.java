package com.lifepilot.agent.task.reminder;

import java.time.Instant;

/**
 * 延后提醒唤醒记录。
 *
 * <p>表示某个主题最近一次决策仍为 {@code DEFER_TO_WINDOW}，
 * 且已经到达下一次评估时间，等待重新进入主动提醒决策。</p>
 *
 * @param decisionId        原始延后决策 ID
 * @param runId             原始执行轮次 ID
 * @param userId            用户 ID
 * @param topicKey          主题键
 * @param title             主题标题
 * @param signalId          原始信号 ID
 * @param candidateType     原始候选类型
 * @param nextEvaluationAt  下次评估时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderDeferredWakeup(
        String decisionId,
        String runId,
        String userId,
        String topicKey,
        String title,
        String signalId,
        String candidateType,
        Instant nextEvaluationAt
) {
}
