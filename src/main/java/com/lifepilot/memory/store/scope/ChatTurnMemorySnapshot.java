package com.lifepilot.memory.store.scope;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        boolean personalLearningEnabled,
        boolean domainLearningEnabled,
        boolean experienceLearningEnabled,
        Map<String, Object> resolutionSource,
        Instant createdAt
) {
    public ChatTurnMemorySnapshot {
        Objects.requireNonNull(turnId, "轮次 ID 不能为空");
        Objects.requireNonNull(sessionId, "会话 ID 不能为空");
        readSpaceIds = List.copyOf(Objects.requireNonNull(readSpaceIds, "读取空间列表不能为空"));
        effectiveKnowledgeBaseIds = List.copyOf(Objects.requireNonNull(
                effectiveKnowledgeBaseIds, "有效知识库列表不能为空"));
        resolutionSource = Map.copyOf(Objects.requireNonNull(resolutionSource, "作用域解析来源不能为空"));
        Objects.requireNonNull(createdAt, "作用域快照创建时间不能为空");
    }
}
