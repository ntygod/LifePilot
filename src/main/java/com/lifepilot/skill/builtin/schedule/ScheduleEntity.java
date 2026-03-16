package com.lifepilot.skill.builtin.schedule;

import org.springframework.lang.Nullable;

/**
 * 日程实体 — 纯数据载体，序列化为 DataStore Document JSON。
 *
 * <p>替代原 {@link ScheduleItem} 中的数据库映射逻辑，
 * 通过 {@link com.lifepilot.datastore.adapter.DataStoreCrudAdapter} 存储到 DataStore。</p>
 *
 * @param title       日程标题
 * @param startTime   开始时间 ISO 8601
 * @param endTime     结束时间 ISO 8601
 * @param recurrence  重复规则（daily / weekly / monthly，可选）
 * @param location    地点（可选）
 * @param description 备注（可选）
 * @author zsg
 * @since 2026-03-20
 */
public record ScheduleEntity(
        String title,
        String startTime,
        String endTime,
        @Nullable String recurrence,
        @Nullable String location,
        @Nullable String description
) {
}
