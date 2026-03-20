package com.lifepilot.memory.workspace;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 任务状态条目草稿。
 *
 * @author zsg
 * @since 2026-03-20
 */
public record TaskStateItem(
        String title,
        String summary,
        @Nullable Map<String, Object> payload,
        int priority,
        @Nullable String taskId,
        @Nullable String sourceTraceId,
        @Nullable Instant expiresAt
) {
}
