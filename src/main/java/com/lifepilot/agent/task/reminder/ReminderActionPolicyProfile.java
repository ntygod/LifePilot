package com.lifepilot.agent.task.reminder;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动提醒动作策略画像。
 *
 * <p>按候选类型和动作汇总近期效果，供动作选择器在
 * {@code SOFT_PUSH / NORMAL_PUSH} 之间做学习性偏好调整。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderActionPolicyProfile {

    private final Map<String, Map<ReminderAction, ReminderActionPerformanceStats>> statsByCandidateType;
    private final Map<ReminderAction, ReminderActionPerformanceStats> globalStats;

    public ReminderActionPolicyProfile(List<ReminderActionPerformanceStats> stats) {
        this.statsByCandidateType = new LinkedHashMap<>();
        this.globalStats = new EnumMap<>(ReminderAction.class);
        if (stats == null) {
            return;
        }
        for (ReminderActionPerformanceStats stat : stats) {
            statsByCandidateType
                    .computeIfAbsent(stat.candidateType(), _ -> new EnumMap<>(ReminderAction.class))
                    .put(stat.action(), stat);
            globalStats.merge(stat.action(), stat, ReminderActionPerformanceStats::merge);
        }
    }

    public ReminderActionPerformanceStats statsFor(ReminderCandidateType candidateType,
                                                   ReminderAction action) {
        String key = candidateType != null ? candidateType.name() : "";
        Map<ReminderAction, ReminderActionPerformanceStats> typedStats = statsByCandidateType.get(key);
        if (typedStats != null && typedStats.containsKey(action)) {
            return typedStats.get(action);
        }
        return globalStats.getOrDefault(action, ReminderActionPerformanceStats.empty(key, action));
    }

    public boolean hasLearningSignal() {
        return globalStats.values().stream().mapToInt(ReminderActionPerformanceStats::sentCount).sum() > 0;
    }

    public int totalSampleCount() {
        return globalStats.values().stream()
                .mapToInt(ReminderActionPerformanceStats::sentCount)
                .sum();
    }
}
