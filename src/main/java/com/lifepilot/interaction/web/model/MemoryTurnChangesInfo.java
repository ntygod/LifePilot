package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 轮次记忆沉淀状态。
 *
 * @author zsg
 * @since 2026-07-06
 */
public record MemoryTurnChangesInfo(
        String status,
        @Nullable String reason,
        List<Map<String, Object>> changes
) {}
