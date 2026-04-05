package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒证据记录。
 *
 * @param id                       证据记录 ID
 * @param decisionId               所属决策 ID
 * @param signalId                 信号 ID
 * @param signalKind               信号类型
 * @param confidenceScore          置信度
 * @param importanceScore          重要度
 * @param evidenceCount            证据数
 * @param observedAt               观察时间
 * @param relevantAt               相关时间点
 * @param preparationLeadMinutes   准备窗口分钟数
 * @param preferredWindowStartHour 偏好窗口开始小时
 * @param preferredWindowEndHour   偏好窗口结束小时
 * @param anomalyScore             异常得分
 * @param actionable               是否可执行
 * @param resolved                 是否已解决
 * @param summary                  摘要
 * @param createdAt                创建时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderEvidenceRecord(
        String id,
        String decisionId,
        String signalId,
        String signalKind,
        float confidenceScore,
        float importanceScore,
        int evidenceCount,
        Instant observedAt,
        @Nullable Instant relevantAt,
        @Nullable Integer preparationLeadMinutes,
        @Nullable Integer preferredWindowStartHour,
        @Nullable Integer preferredWindowEndHour,
        float anomalyScore,
        boolean actionable,
        boolean resolved,
        @Nullable String summary,
        Instant createdAt
) {
}
