package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.preference.PreferenceEntry;
import com.lifepilot.agent.task.proactive.schedule.ScheduleEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨行为共享上下文 — 多个插件在同一心跳中共享状态。
 *
 * <p>在一次心跳的 detect → reason 流程中，前面插件的产出可供后续插件参考。
 * 例如 FollowUpBehavior 检测到活跃意图后，InsightBehavior 可据此关联洞察。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class SharedBehaviorContext {

    /** 各插件在本次心跳中产出的候选数量。 */
    private final Map<String, Integer> candidateCounts = new ConcurrentHashMap<>();

    /** 本次心跳中活跃的用户偏好快照。 */
    private List<PreferenceEntry> userPreferences = List.of();

    /** 本次心跳中提取的日程事件。 */
    private List<ScheduleEvent> scheduleEvents = List.of();

    /** 记录插件候选数。 */
    public void recordCandidateCount(String behaviorName, int count) {
        candidateCounts.put(behaviorName, count);
    }

    /** 获取插件候选数（0 表示该插件未产出或不存在）。 */
    public int getCandidateCount(String behaviorName) {
        return candidateCounts.getOrDefault(behaviorName, 0);
    }

    /** 是否有任何插件产出了候选。 */
    public boolean hasAnyCandidates() {
        return candidateCounts.values().stream().anyMatch(c -> c > 0);
    }

    public void setUserPreferences(List<PreferenceEntry> prefs) {
        this.userPreferences = prefs != null ? List.copyOf(prefs) : List.of();
    }

    public List<PreferenceEntry> getUserPreferences() { return userPreferences; }

    public void setScheduleEvents(List<ScheduleEvent> events) {
        this.scheduleEvents = events != null ? List.copyOf(events) : List.of();
    }

    public List<ScheduleEvent> getScheduleEvents() { return scheduleEvents; }
}
