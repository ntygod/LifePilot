package com.lifepilot.knowledge.sync;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * 知识同步任务。
 *
 * @author zsg
 * @since 2026-03-26
 */
public record KnowledgeSyncJob(
        String id,
        KnowledgeSyncJobType jobType,
        String knowledgeBaseId,
        String datastoreId,
        @Nullable String sourceKey,
        @Nullable String sourceVersion,
        Map<String, Object> payload,
        KnowledgeSyncJobStatus status,
        int attemptCount,
        @Nullable String lastError,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt
) {
}
