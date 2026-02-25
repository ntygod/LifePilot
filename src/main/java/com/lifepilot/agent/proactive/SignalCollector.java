package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 信号收集器 — 从任务、日程、习惯、行为数据源收集信号。
 *
 * <p>每个数据源独立 try-catch，单个数据源故障不阻塞整个信号收集流程。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SignalCollector {

    private static final Logger log = LoggerFactory.getLogger(SignalCollector.class);

    private final TodoRepository todoRepository;
    private final ScheduleRepository scheduleRepository;
    private final HabitRepository habitRepository;
    private final EpisodicMemory episodicMemory;
    private final JdbcTemplate jdbcTemplate;

    public SignalCollector(TodoRepository todoRepository,
                           ScheduleRepository scheduleRepository,
                           HabitRepository habitRepository,
                           EpisodicMemory episodicMemory,
                           JdbcTemplate jdbcTemplate) {
        this.todoRepository = todoRepository;
        this.scheduleRepository = scheduleRepository;
        this.habitRepository = habitRepository;
        this.episodicMemory = episodicMemory;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 收集所有信号，返回不可变 SignalBundle。
     *
     * @return 信号包
     */
    public SignalBundle collect() {
        var now = LocalDateTime.now();
        var nowInstant = Instant.now();

        // 任务信号：24 小时内到期的 PENDING/IN_PROGRESS 待办
        List<TodoItem> upcomingDeadlines = collectUpcomingDeadlines(nowInstant);

        // 日程信号：2 小时内开始的日程
        List<ScheduleItem> upcomingSchedules = collectUpcomingSchedules(nowInstant);

        // 习惯信号
        List<HabitItem> allHabits = collectAllHabits();
        List<String> checkedTodayIds = collectCheckedTodayIds();

        // 今天未打卡的习惯
        List<HabitItem> pendingHabits = allHabits.stream()
                .filter(h -> !checkedTodayIds.contains(h.id()))
                .toList();

        // 连续打卡风险：current_streak > 0 且今天未打卡
        List<HabitItem> streaksAtRisk = pendingHabits.stream()
                .filter(h -> h.currentStreak() > 0)
                .toList();

        // 行为信号：最近 24 小时对话数量
        int recentConversationCount = collectRecentConversationCount();

        // 距上次交互时长
        Duration timeSinceLastInteraction = collectTimeSinceLastInteraction(nowInstant);

        return SignalBundle.builder()
                .currentTime(now)
                .dayOfWeek(now.getDayOfWeek())
                .timeSinceLastInteraction(timeSinceLastInteraction)
                .upcomingDeadlines(upcomingDeadlines)
                .upcomingSchedules(upcomingSchedules)
                .pendingHabits(pendingHabits)
                .streaksAtRisk(streaksAtRisk)
                .recentConversationCount(recentConversationCount)
                .build();
    }

    /** 收集 24 小时内到期的 PENDING/IN_PROGRESS 待办。 */
    private List<TodoItem> collectUpcomingDeadlines(Instant now) {
        try {
            var deadline24h = now.plus(Duration.ofHours(24));
            // 查询 PENDING 和 IN_PROGRESS 状态的待办
            var pending = todoRepository.list("PENDING", null);
            var inProgress = todoRepository.list("IN_PROGRESS", null);

            var all = new java.util.ArrayList<>(pending);
            all.addAll(inProgress);

            return all.stream()
                    .filter(t -> t.dueDate() != null)
                    .filter(t -> {
                        try {
                            var due = Instant.parse(t.dueDate());
                            return due.isAfter(now) && due.isBefore(deadline24h);
                        } catch (DateTimeParseException e) {
                            log.debug("待办截止日期解析失败: id={}, dueDate={}", t.id(), t.dueDate());
                            return false;
                        }
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("待办信号收集失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 收集 2 小时内开始的日程。 */
    private List<ScheduleItem> collectUpcomingSchedules(Instant now) {
        try {
            var twoHoursLater = now.plus(Duration.ofHours(2));
            return scheduleRepository.findConflicts(now.toString(), twoHoursLater.toString());
        } catch (Exception e) {
            log.warn("日程信号收集失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 收集所有习惯。 */
    private List<HabitItem> collectAllHabits() {
        try {
            return habitRepository.list();
        } catch (Exception e) {
            log.warn("习惯信号收集失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 查询今天已打卡的习惯 ID 列表。 */
    private List<String> collectCheckedTodayIds() {
        try {
            String todayPrefix = LocalDate.now(ZoneId.systemDefault()).toString();
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT habit_id FROM habit_logs WHERE checked_at >= ?",
                    String.class, todayPrefix + "T00:00:00Z");
        } catch (Exception e) {
            log.warn("习惯打卡记录查询失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 收集最近 24 小时对话数量。 */
    private int collectRecentConversationCount() {
        try {
            var recent = episodicMemory.getRecent(100);
            var cutoff = Instant.now().minus(Duration.ofHours(24));
            return (int) recent.stream()
                    .filter(c -> c.createdAt().isAfter(cutoff))
                    .count();
        } catch (Exception e) {
            log.warn("行为信号收集失败: error={}", e.getMessage());
            return 0;
        }
    }

    /** 计算距上次交互的时长。 */
    private Duration collectTimeSinceLastInteraction(Instant now) {
        try {
            var recent = episodicMemory.getRecent(1);
            if (recent.isEmpty()) {
                return Duration.ofDays(999);
            }
            return Duration.between(recent.getFirst().createdAt(), now);
        } catch (Exception e) {
            log.warn("交互时长计算失败: error={}", e.getMessage());
            return Duration.ofDays(999);
        }
    }
}
