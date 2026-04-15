package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 主动引擎配置 DTO — 返回给前端的全局配置信息。
 *
 * @param enabled            主动引擎总开关
 * @param dailyMaxReminders  每日提醒上限
 * @param quietHoursStart    静默开始时间（HH:mm）
 * @param quietHoursEnd      静默结束时间（HH:mm）
 * @param gate2Threshold     Gate 2 阈值
 * @param behaviors          各行为的自主度配置
 * @author zsg
 * @since 2026-04-15
 */
public record ProactiveConfigDto(
        boolean enabled,
        int dailyMaxReminders,
        @Nullable String quietHoursStart,
        @Nullable String quietHoursEnd,
        float gate2Threshold,
        List<BehaviorConfigDto> behaviors
) {
    /**
     * 单个行为的自主度配置。
     *
     * @param name           行为插件名称
     * @param label          行为中文标签
     * @param autonomyLevel  自主度级别
     */
    public record BehaviorConfigDto(
            String name,
            String label,
            String autonomyLevel
    ) {}
}
