package com.lifepilot.agent.proactive.model;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import lombok.Builder;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 信号包 — SignalCollector 的输出，不可变数据载体。
 *
 * <p>包含时间信号、任务信号、日程信号、习惯信号和行为信号。
 * 所有集合字段在构造时通过 {@link List#copyOf(java.util.Collection)} 确保不可变。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record SignalBundle(
        // 时间信号
        LocalDateTime currentTime,
        DayOfWeek dayOfWeek,
        Duration timeSinceLastInteraction,

        // 任务信号：24 小时内到期的 PENDING/IN_PROGRESS 待办
        List<TodoItem> upcomingDeadlines,

        // 日程信号：2 小时内开始的日程
        List<ScheduleItem> upcomingSchedules,

        // 习惯信号：今天未打卡的习惯
        List<HabitItem> pendingHabits,

        // 连续打卡风险：current_streak > 0 且今天未打卡
        List<HabitItem> streaksAtRisk,

        // 行为信号：最近 24 小时对话数量
        int recentConversationCount
) {

    /** 紧凑构造函数 — 确保集合不可变。 */
    public SignalBundle {
        upcomingDeadlines = upcomingDeadlines != null ? List.copyOf(upcomingDeadlines) : List.of();
        upcomingSchedules = upcomingSchedules != null ? List.copyOf(upcomingSchedules) : List.of();
        pendingHabits = pendingHabits != null ? List.copyOf(pendingHabits) : List.of();
        streaksAtRisk = streaksAtRisk != null ? List.copyOf(streaksAtRisk) : List.of();
    }
}
