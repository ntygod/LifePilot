package com.lifepilot.skill.builtin.habit;

import org.springframework.lang.Nullable;

/**
 * 习惯。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record HabitItem(
        String id,
        String name,
        Frequency frequency,
        @Nullable String targetTime,
        int currentStreak,
        String createdAt,
        String updatedAt
) {

    /** 习惯频率。 */
    public enum Frequency { DAILY, WEEKLY }
}
