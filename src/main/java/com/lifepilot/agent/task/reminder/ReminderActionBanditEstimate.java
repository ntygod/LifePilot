package com.lifepilot.agent.task.reminder;

/**
 * 动作 Bandit 估计结果。
 *
 * @param action             动作
 * @param expectedReward     期望收益
 * @param uncertainty        不确定性
 * @param optimisticReward   乐观收益
 * @param conservativeReward 保守收益
 * @param sampleCount        训练样本数
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderActionBanditEstimate(
        ReminderAction action,
        float expectedReward,
        float uncertainty,
        float optimisticReward,
        float conservativeReward,
        int sampleCount
) {
}
