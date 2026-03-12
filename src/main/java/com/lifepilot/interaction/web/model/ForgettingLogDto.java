package com.lifepilot.interaction.web.model;

import java.time.Instant;

/**
 * 遗忘日志 DTO。
 *
 * @author zsg
 * @since 2026-03-13
 */
public record ForgettingLogDto(
        String id,
        String entityId,
        String entityName,
        String strategy,
        String actionTaken,
        float forgettingPriority,
        String reason,
        Instant createdAt
) {}
