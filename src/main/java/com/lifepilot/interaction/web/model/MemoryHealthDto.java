package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * 记忆系统健康度指标 DTO — 各层统计、遗忘日志、访问活跃度。
 *
 * @author zsg
 * @since 2026-04-16
 */
public record MemoryHealthDto(
        long totalEntities,
        long totalRelations,
        Map<String, Long> entityCountByType,
        long conversationCount,
        long templateCount,
        long preferenceRuleCount,
        long experienceCount,
        long preferenceEntityCount,
        long habitCount,
        long goalCount,
        long forgettingLogCount,
        @Nullable String lastForgettingTime,
        float recentAccessRatio,
        float avgImportanceScore
) {}
