package com.lifepilot.agent.intelligence.model;

import java.time.Instant;
import java.util.Map;

/**
 * 环境状态快照 — 当前执行环境的感知结果。
 *
 * @author zsg
 * @since 2026-06-01
 */
public record EnvironmentState(
    UserActivityLevel userActivity,
    TimeContext timeContext,
    Map<String, ToolHealth> toolHealth,
    Instant observedAt
) {
    public static EnvironmentState defaults() {
        return new EnvironmentState(
            UserActivityLevel.ACTIVE,
            TimeContext.now(),
            Map.of(),
            Instant.now()
        );
    }

    public enum UserActivityLevel {
        ACTIVE,
        IDLE,
        AWAY,
        FOCUSED
    }

    public record TimeContext(
        int hourOfDay,
        int dayOfWeek,
        boolean isWorkingHours
    ) {
        public static TimeContext now() {
            var now = java.time.LocalDateTime.now();
            int hour = now.getHour();
            boolean working = hour >= 9 && hour <= 18 && now.getDayOfWeek().getValue() <= 5;
            return new TimeContext(hour, now.getDayOfWeek().getValue(), working);
        }
    }
}
