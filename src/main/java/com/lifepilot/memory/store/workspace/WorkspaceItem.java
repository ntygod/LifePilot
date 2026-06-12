package com.lifepilot.memory.store.workspace;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 工作区持久化条目。
 *
 * @author zsg
 * @since 2026-03-20
 */
public record WorkspaceItem(
        String id,
        String sessionId,
        WorkspaceItemKind kind,
        String title,
        String summary,
        @Nullable String payloadJson,
        WorkspaceStatus status,
        int priority,
        @Nullable String taskId,
        @Nullable String sourceTraceId,
        @Nullable Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean isActiveAt(Instant now) {
        return status == WorkspaceStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(now));
    }
}
