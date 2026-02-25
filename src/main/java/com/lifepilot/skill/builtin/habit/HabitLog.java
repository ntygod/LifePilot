package com.lifepilot.skill.builtin.habit;

/**
 * 打卡记录。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record HabitLog(String id, String habitId, String checkedAt, String createdAt) {}
