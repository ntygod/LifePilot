package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒离线回放报告记录。
 *
 * <p>用于保存系统周期性 replay 评估结果，供内部调参与回归分析使用。</p>
 *
 * @param id                               记录 ID
 * @param userId                           用户 ID
 * @param since                            回放起点
 * @param generatedAt                      报告生成时间
 * @param sampleCount                      样本数
 * @param historicalPushCount              历史推送数
 * @param replayedPushCount                回放推送数
 * @param suppressedCount                  被压制数
 * @param promotedCount                    被补发数
 * @param actionShiftCount                 动作漂移数
 * @param historicalObservedRewardMean     历史真实奖励均值
 * @param historicalEstimatedPushRewardMean 历史估计推送收益均值
 * @param replayedEstimatedPushRewardMean  回放估计推送收益均值
 * @param actionShiftJson                  动作迁移矩阵 JSON
 * @param summaryJson                      报告摘要 JSON
 * @param createdAt                        创建时间
 * @author zsg
 * @since 2026-03-29
 */
public record ReminderReplayReportRecord(
        String id,
        String userId,
        Instant since,
        Instant generatedAt,
        int sampleCount,
        int historicalPushCount,
        int replayedPushCount,
        int suppressedCount,
        int promotedCount,
        int actionShiftCount,
        float historicalObservedRewardMean,
        float historicalEstimatedPushRewardMean,
        float replayedEstimatedPushRewardMean,
        @Nullable String actionShiftJson,
        @Nullable String summaryJson,
        Instant createdAt
) {
}
