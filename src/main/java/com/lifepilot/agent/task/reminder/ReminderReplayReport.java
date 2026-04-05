package com.lifepilot.agent.task.reminder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 提醒策略离线回放报告。
 *
 * <p>仅供系统内部使用，区分历史真实奖励与策略估计收益。</p>
 *
 * @param userId                           用户 ID
 * @param since                            回放起点
 * @param sampleCount                      样本数
 * @param historicalPushCount              历史推送数
 * @param replayedPushCount                回放推送数
 * @param suppressedCount                  被新策略压制的推送数
 * @param promotedCount                    被新策略补发的推送数
 * @param actionShiftCount                 动作漂移数
 * @param historicalObservedRewardMean     历史真实奖励均值
 * @param historicalEstimatedPushRewardMean 历史动作估计收益均值
 * @param replayedEstimatedPushRewardMean  回放动作估计收益均值
 * @param actionShiftMatrix                动作迁移矩阵
 * @param evaluations                      单条回放结果
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderReplayReport(
        String userId,
        Instant since,
        int sampleCount,
        int historicalPushCount,
        int replayedPushCount,
        int suppressedCount,
        int promotedCount,
        int actionShiftCount,
        float historicalObservedRewardMean,
        float historicalEstimatedPushRewardMean,
        float replayedEstimatedPushRewardMean,
        Map<String, Integer> actionShiftMatrix,
        List<ReminderReplayEvaluation> evaluations
) {

    public static ReminderReplayReport empty(String userId, Instant since) {
        return new ReminderReplayReport(
                userId,
                since,
                0,
                0,
                0,
                0,
                0,
                0,
                0.0f,
                0.0f,
                0.0f,
                Map.of(),
                List.of()
        );
    }
}
