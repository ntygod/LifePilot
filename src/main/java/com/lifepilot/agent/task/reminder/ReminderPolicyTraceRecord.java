package com.lifepilot.agent.task.reminder;

import java.time.Instant;

/**
 * 提醒策略追踪落库记录。
 *
 * @param decisionId               关联决策 ID
 * @param baseAction               初始动作
 * @param opportunityAction        机会学习后动作
 * @param finalAction              最终动作
 * @param opportunityAdjusted      是否被机会学习调整
 * @param actionAdjusted           是否被动作学习调整
 * @param trainingExampleCount     训练样本数
 * @param actionFeedbackSampleCount 动作反馈样本数
 * @param traceJson                结构化追踪 JSON
 * @param createdAt                创建时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderPolicyTraceRecord(
        String decisionId,
        String baseAction,
        String opportunityAction,
        String finalAction,
        boolean opportunityAdjusted,
        boolean actionAdjusted,
        int trainingExampleCount,
        int actionFeedbackSampleCount,
        String traceJson,
        Instant createdAt
) {
}
