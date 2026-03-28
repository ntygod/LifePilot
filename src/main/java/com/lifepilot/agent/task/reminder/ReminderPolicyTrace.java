package com.lifepilot.agent.task.reminder;

import java.util.Objects;

/**
 * 提醒学习策略追踪信息。
 *
 * <p>记录一次提醒决策在机会学习和动作学习阶段的内部演化结果，
 * 供系统回放、调参与自诊断使用。</p>
 *
 * @param baseAction               规则层初始动作
 * @param opportunityAction        机会学习后的动作
 * @param finalAction              最终动作
 * @param opportunityAdjusted      是否被机会学习调整
 * @param actionAdjusted           是否被动作学习调整
 * @param trainingExampleCount     使用的训练样本数
 * @param actionFeedbackSampleCount 动作画像中的反馈样本数
 * @param summary                  追踪摘要
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderPolicyTrace(
        ReminderAction baseAction,
        ReminderAction opportunityAction,
        ReminderAction finalAction,
        boolean opportunityAdjusted,
        boolean actionAdjusted,
        int trainingExampleCount,
        int actionFeedbackSampleCount,
        String summary
) {

    public ReminderPolicyTrace {
        baseAction = Objects.requireNonNullElse(baseAction, ReminderAction.SKIP);
        opportunityAction = Objects.requireNonNullElse(opportunityAction, baseAction);
        finalAction = Objects.requireNonNullElse(finalAction, opportunityAction);
        trainingExampleCount = Math.max(0, trainingExampleCount);
        actionFeedbackSampleCount = Math.max(0, actionFeedbackSampleCount);
        summary = Objects.requireNonNullElse(summary, "");
    }
}
