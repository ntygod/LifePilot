package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 主动引擎配置更新请求。
 *
 * @param enabled            主动引擎总开关（null 表示不修改）
 * @param dailyMaxReminders  每日提醒上限（null 表示不修改）
 * @param quietHoursStart    静默开始时间（null 表示不修改）
 * @param quietHoursEnd      静默结束时间（null 表示不修改）
 * @param behaviorOverrides  行为自主度覆盖列表（null 表示不修改）
 * @author zsg
 * @since 2026-04-15
 */
public record ProactiveConfigUpdateRequest(
        @Nullable Boolean enabled,
        @Nullable Integer dailyMaxReminders,
        @Nullable String quietHoursStart,
        @Nullable String quietHoursEnd,
        @Nullable List<BehaviorOverride> behaviorOverrides
) {
    /**
     * 单个行为的自主度覆盖。
     *
     * @param name           行为插件名称
     * @param autonomyLevel  目标自主度级别（null 表示不修改）
     */
    public record BehaviorOverride(String name, @Nullable String autonomyLevel) {}
}
