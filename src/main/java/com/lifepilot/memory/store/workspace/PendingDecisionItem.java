package com.lifepilot.memory.store.workspace;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 待确认条目草稿。
 *
 * @author zsg
 * @since 2026-03-20
 */
public record PendingDecisionItem(
        String title,
        String summary,
        @Nullable Map<String, Object> payload,
        int priority,
        @Nullable String taskId,
        @Nullable String sourceTraceId,
        @Nullable Instant expiresAt
) {
}
