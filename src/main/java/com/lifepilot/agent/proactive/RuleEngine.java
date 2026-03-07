package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.NotificationType;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.agent.proactive.model.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎 — Stage 1 确定性过滤，纯规则逻辑，不依赖 LLM。
 *
 * <p>过滤规则链：免打扰时段 → 类型开关 → 冷却期 → 信号阈值映射。
 * 目标执行时间 &lt; 10ms。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final FrequencyStateManager frequencyStateManager;
    private final ProactiveConfigProperties config;

    public RuleEngine(FrequencyStateManager frequencyStateManager, ProactiveConfigProperties config) {
        this.frequencyStateManager = frequencyStateManager;
        this.config = config;
    }

    /**
     * 评估信号包，生成候选提醒列表。
     *
     * @param signals 信号包
     * @return 不可变候选列表
     */
    public List<ProactiveCandidate> evaluate(SignalBundle signals) {
        // 1. 免打扰时段检查
        if (isInQuietHours(signals.currentTime().toLocalTime())) {
            log.debug("当前处于免打扰时段，跳过所有候选");
            return List.of();
        }

        var candidates = new ArrayList<ProactiveCandidate>();

        // 2. 待办截止提醒
        for (var todo : signals.upcomingDeadlines()) {
            if (!isTypeAllowed(NotificationType.DEADLINE_REMINDER)) continue;
            try {
                var due = Instant.parse(todo.dueDate());
                var hoursUntilDue = Duration.between(Instant.now(), due).toHours();
                Urgency urgency;
                if (hoursUntilDue <= 2) {
                    urgency = Urgency.HIGH;
                } else if (hoursUntilDue <= 12) {
                    urgency = Urgency.MEDIUM;
                } else {
                    urgency = Urgency.LOW;
                }
                candidates.add(new ProactiveCandidate(
                        NotificationType.DEADLINE_REMINDER, urgency,
                        "待办「%s」将在 %d 小时内到期".formatted(todo.title(), hoursUntilDue)));
            } catch (DateTimeParseException e) {
                log.debug("待办截止日期解析失败: id={}", todo.id());
            }
        }

        // 3. 日程开始提醒
        for (var schedule : signals.upcomingSchedules()) {
            if (!isTypeAllowed(NotificationType.SCHEDULE_REMINDER)) continue;
            try {
                var start = Instant.parse(schedule.startTime());
                var minutesUntilStart = Duration.between(Instant.now(), start).toMinutes();
                // ≤30 分钟 → HIGH
                if (minutesUntilStart <= 30) {
                    candidates.add(new ProactiveCandidate(
                            NotificationType.SCHEDULE_REMINDER, Urgency.HIGH,
                            "日程「%s」将在 %d 分钟后开始".formatted(schedule.title(), minutesUntilStart)));
                }
            } catch (DateTimeParseException e) {
                log.debug("日程开始时间解析失败: id={}", schedule.id());
            }
        }

        // 4. 习惯未打卡提醒 → LOW
        for (var habit : signals.pendingHabits()) {
            if (!isTypeAllowed(NotificationType.HABIT_REMINDER)) continue;
            candidates.add(new ProactiveCandidate(
                    NotificationType.HABIT_REMINDER, Urgency.LOW,
                    "习惯「%s」今天还未打卡".formatted(habit.name())));
        }

        // 5. 连续打卡风险 → MEDIUM
        for (var habit : signals.streaksAtRisk()) {
            if (!isTypeAllowed(NotificationType.STREAK_AT_RISK)) continue;
            candidates.add(new ProactiveCandidate(
                    NotificationType.STREAK_AT_RISK, Urgency.MEDIUM,
                    "习惯「%s」连续 %d 天打卡记录面临中断".formatted(habit.name(), habit.currentStreak())));
        }

        // 6. 每日总结规则
        if (isTypeAllowed(NotificationType.DAILY_SUMMARY)) {
            if (signals.currentTime().getHour() == config.getDailySummaryHour()
                    && !hasSentToday(NotificationType.DAILY_SUMMARY, signals.currentTime().toLocalDate())) {
                candidates.add(new ProactiveCandidate(
                        NotificationType.DAILY_SUMMARY, Urgency.LOW, "每日总结时间到"));
            }
        }

        // 7. 每周回顾规则
        if (isTypeAllowed(NotificationType.WEEKLY_REVIEW)) {
            if (signals.dayOfWeek().getValue() == config.getWeeklyReviewDay()
                    && signals.currentTime().getHour() == config.getWeeklyReviewHour()
                    && !hasSentThisWeek(NotificationType.WEEKLY_REVIEW, signals.currentTime().toLocalDate())) {
                candidates.add(new ProactiveCandidate(
                        NotificationType.WEEKLY_REVIEW, Urgency.LOW, "每周回顾时间到"));
            }
        }

        return List.copyOf(candidates);
    }

    /**
     * 判断当前时间是否在免打扰时段内。
     * 支持跨午夜的时段（如 22:00 ~ 08:00）。
     */
    private boolean isInQuietHours(LocalTime time) {
        var start = LocalTime.of(config.getQuietHoursStart(), 0);
        var end = LocalTime.of(config.getQuietHoursEnd(), 0);

        if (start.isBefore(end)) {
            // 不跨午夜：如 08:00 ~ 22:00
            return !time.isBefore(start) && time.isBefore(end);
        } else {
            // 跨午夜：如 22:00 ~ 08:00
            return !time.isBefore(start) || time.isBefore(end);
        }
    }

    /**
     * 判断指定通知类型是否允许发送（类型开关 + 冷却期）。
     */
    private boolean isTypeAllowed(NotificationType type) {
        if (!config.isTypeEnabled(type)) {
            return false;
        }
        return !frequencyStateManager.isInCooldown(type);
    }

    /**
     * 判断指定类型今天是否已发送过通知。
     */
    private boolean hasSentToday(NotificationType type, LocalDate today) {
        var lastNotified = frequencyStateManager.getLastNotifiedAt(type);
        if (lastNotified.equals(Instant.EPOCH)) {
            return false;
        }
        var lastDate = LocalDate.ofInstant(lastNotified, ZoneId.systemDefault());
        return !lastDate.isBefore(today);
    }

    /**
     * 判断指定类型本周是否已发送过通知。
     */
    private boolean hasSentThisWeek(NotificationType type, LocalDate today) {
        var lastNotified = frequencyStateManager.getLastNotifiedAt(type);
        if (lastNotified.equals(Instant.EPOCH)) {
            return false;
        }
        var lastDate = LocalDate.ofInstant(lastNotified, ZoneId.systemDefault());
        // 本周一作为周起始
        var weekStart = today.with(java.time.DayOfWeek.MONDAY);
        return !lastDate.isBefore(weekStart);
    }
}
