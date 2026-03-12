package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * 记忆统计概览 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record MemoryStatsDto(
        long conversationCount,
        long entityCount,
        Map<String, Long> entityCountByType,
        long relationCount,
        long templateCount,
        long preferenceCount,
        long forgettingLogCount,
        @Nullable String lastForgettingTime
) {}
