package com.lifepilot.skill.builtin.habit;

import org.springframework.lang.Nullable;

/**
 * 习惯实体 — 纯数据载体，序列化为 DataStore Document JSON。
 *
 * <p>替代原 {@link HabitItem} 中的数据库映射逻辑，
 * 通过 {@link com.lifepilot.datastore.adapter.DataStoreCrudAdapter} 存储到 DataStore。</p>
 *
 * @param name            习惯名称
 * @param frequency       频率（DAILY / WEEKLY）
 * @param targetCount     目标打卡次数（默认 0）
 * @param currentStreak   当前连续打卡天数
 * @param lastCompletedAt 最后完成时间 ISO 8601（可选）
 * @author zsg
 * @since 2026-03-22
 */
public record HabitEntity(
        String name,
        String frequency,
        int targetCount,
        int currentStreak,
        @Nullable String lastCompletedAt
) {
}
