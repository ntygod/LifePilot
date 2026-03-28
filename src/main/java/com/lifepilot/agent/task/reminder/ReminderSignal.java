package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 提醒归一化信号。
 *
 * <p>表示采集层输出给算法层的最小输入单元。
 * 该模型只表达事实，不表达提醒动作。</p>
 *
 * @param signalId                 信号 ID
 * @param kind                     信号类别
 * @param confidenceScore          置信度
 * @param importanceScore          重要性
 * @param evidenceCount            证据数
 * @param observedAt               观察时间
 * @param relevantAt               相关时间点（截止时间/事件时间）
 * @param preparationLeadTime      准备窗口长度
 * @param preferredWindowStartHour 偏好窗口开始小时
 * @param preferredWindowEndHour   偏好窗口结束小时
 * @param anomalyScore             异常分
 * @param actionable               是否存在明确动作
 * @param resolved                 是否已解决
 * @param summary                  摘要
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderSignal(
        String signalId,
        ReminderSignalKind kind,
        float confidenceScore,
        float importanceScore,
        int evidenceCount,
        Instant observedAt,
        @Nullable Instant relevantAt,
        @Nullable Duration preparationLeadTime,
        @Nullable Integer preferredWindowStartHour,
        @Nullable Integer preferredWindowEndHour,
        float anomalyScore,
        boolean actionable,
        boolean resolved,
        @Nullable String summary
) {

    public ReminderSignal {
        signalId = Objects.requireNonNull(signalId, "signalId 不能为空");
        kind = Objects.requireNonNull(kind, "kind 不能为空");
        observedAt = Objects.requireNonNull(observedAt, "observedAt 不能为空");
        confidenceScore = clamp(confidenceScore);
        importanceScore = clamp(importanceScore);
        evidenceCount = Math.max(1, evidenceCount);
        anomalyScore = clamp(anomalyScore);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
