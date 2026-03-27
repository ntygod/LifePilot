package com.lifepilot.memory.scope;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话轮次记忆作用域快照。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record ChatTurnMemorySnapshot(
        String turnId,
        String sessionId,
        @Nullable String personalSpaceId,
        @Nullable String experienceSpaceId,
        @Nullable String domainWriteSpaceId,
        List<String> readSpaceIds,
        List<String> effectiveKnowledgeBaseIds,
        List<String> effectiveDatastoreIds,
        boolean personalLearningEnabled,
        boolean domainLearningEnabled,
        boolean experienceLearningEnabled,
        Map<String, Object> resolutionSource,
        Instant createdAt
) {
    public ChatTurnMemorySnapshot {
        readSpaceIds = readSpaceIds != null ? List.copyOf(readSpaceIds) : List.of();
        effectiveKnowledgeBaseIds = effectiveKnowledgeBaseIds != null ? List.copyOf(effectiveKnowledgeBaseIds) : List.of();
        effectiveDatastoreIds = effectiveDatastoreIds != null ? List.copyOf(effectiveDatastoreIds) : List.of();
        resolutionSource = resolutionSource != null ? Map.copyOf(resolutionSource) : Map.of();
    }
}
