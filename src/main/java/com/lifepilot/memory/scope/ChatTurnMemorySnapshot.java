package com.lifepilot.memory.scope;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话轮次记忆作用域快照。
 *
 * <p>{@code projectSpaceId} — 当前轮次归属的隔离项目 MemorySpace id（ISOLATED 项目对话时非空；
 * SHARED 项目 / 主账户对话留 null）。下游 RealtimeExtractor / 经验写入路径从本字段决定
 * writeContext.spaceId，实现对话级记忆写入的项目隔离。</p>
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
        @Nullable String projectSpaceId,
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
