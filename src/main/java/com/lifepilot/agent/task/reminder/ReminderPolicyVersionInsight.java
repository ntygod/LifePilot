package com.lifepilot.agent.task.reminder;

/**
 * 主动提醒策略版本洞察。
 *
 * <p>从历史版本摘要中提炼出可供护栏判断的方向性信号。</p>
 *
 * @param direction     历史版本对应的调整方向
 * @param sampleCount   回放样本数
 * @param expectedDelta 历史回放的预期收益变化
 * @param hasReplay     是否包含回放信号
 * @author zsg
 * @since 2026-03-29
 */
public record ReminderPolicyVersionInsight(
        ReminderPolicyAdjustmentDirection direction,
        int sampleCount,
        float expectedDelta,
        boolean hasReplay
) {

    public ReminderPolicyVersionInsight {
        direction = direction != null ? direction : ReminderPolicyAdjustmentDirection.NEUTRAL;
    }

    public static ReminderPolicyVersionInsight empty() {
        return new ReminderPolicyVersionInsight(ReminderPolicyAdjustmentDirection.NEUTRAL, 0, 0.0f, false);
    }
}
