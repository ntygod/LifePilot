package com.lifepilot.memory.store.event;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 记忆域事件模型。
 *
 * <p>用于解耦 transcript/session 事实写入与记忆模块消费链路。
 * 当前阶段先建立稳定事件类型与统一发布接口，后续再逐步把实时学习、
 * 巩固、反馈、压缩等能力切换到事件驱动模型。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public sealed interface MemoryEvent permits
        MemoryEvent.SessionStarted,
        MemoryEvent.TranscriptEntryCommitted,
        MemoryEvent.TurnCommitted,
        MemoryEvent.AssistantReplyCommitted,
        MemoryEvent.ToolCallCommitted,
        MemoryEvent.ToolResultCommitted,
        MemoryEvent.ArtifactCommitted,
        MemoryEvent.TraceCompleted,
        MemoryEvent.MemoryInjected,
        MemoryEvent.MessageFeedbackReceived,
        MemoryEvent.SessionIdle,
        MemoryEvent.CompactionRequested,
        MemoryEvent.CompactionCommitted,
        MemoryEvent.PreCompactionMemoryFlushRequested,
        MemoryEvent.PreCompactionMemoryFlushCommitted {

    String sessionId();

    Instant occurredAt();

    default String eventType() {
        return getClass().getSimpleName();
    }

    /**
     * 会话已创建或首次激活。
     */
    record SessionStarted(
            String sessionId,
            String channel,
            String title,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * Transcript 条目已写入。
     */
    record TranscriptEntryCommitted(
            String sessionId,
            String entryId,
            String entryType,
            @Nullable String role,
            @Nullable String turnId,
            @Nullable String traceId,
            boolean visibleToModel,
            boolean visibleToUser,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 一轮对话已完成提交。
     */
    record TurnCommitted(
            String sessionId,
            @Nullable String turnId,
            @Nullable String traceId,
            @Nullable String userEntryId,
            @Nullable String assistantEntryId,
            @Nullable String assistantText,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 助手回复已提交。
     */
    record AssistantReplyCommitted(
            String sessionId,
            @Nullable String turnId,
            @Nullable String traceId,
            String assistantEntryId,
            String assistantText,
            @Nullable String reasoningSummary,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 工具调用已提交。
     */
    record ToolCallCommitted(
            String sessionId,
            @Nullable String turnId,
            @Nullable String traceId,
            String entryId,
            String toolId,
            @Nullable String callId,
            @Nullable String inputJson,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 工具结果已提交。
     */
    record ToolResultCommitted(
            String sessionId,
            @Nullable String turnId,
            @Nullable String traceId,
            String entryId,
            String toolId,
            @Nullable String callId,
            boolean success,
            @Nullable String outputJson,
            @Nullable String artifactId,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * Artifact 已提交。
     */
    record ArtifactCommitted(
            String sessionId,
            String artifactId,
            @Nullable String sourceEntryId,
            @Nullable String traceId,
            String artifactType,
            @Nullable String title,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * Trace 已完成。
     */
    record TraceCompleted(
            String sessionId,
            String traceId,
            boolean success,
            int outputTokens,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 记忆注入已发生。
     */
    record MemoryInjected(
            String sessionId,
            @Nullable String traceId,
            @Nullable String turnId,
            @Nullable String assistantEntryId,
            List<String> injectedEntityIds,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 用户反馈已提交。
     */
    record MessageFeedbackReceived(
            String sessionId,
            @Nullable String assistantEntryId,
            @Nullable String feedbackEntryId,
            String feedbackType,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 会话进入空闲态。
     */
    record SessionIdle(
            String sessionId,
            Instant idleSince,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 请求压缩。
     */
    record CompactionRequested(
            String sessionId,
            @Nullable String branchId,
            String trigger,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 压缩已完成。
     */
    record CompactionCommitted(
            String sessionId,
            @Nullable String branchId,
            String compactionEntryId,
            @Nullable String firstKeptEntryId,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 请求压缩前记忆刷新。
     */
    record PreCompactionMemoryFlushRequested(
            String sessionId,
            @Nullable String branchId,
            String trigger,
            Instant occurredAt
    ) implements MemoryEvent {
    }

    /**
     * 压缩前记忆刷新已完成。
     */
    record PreCompactionMemoryFlushCommitted(
            String sessionId,
            @Nullable String branchId,
            List<String> documentIds,
            Instant occurredAt
    ) implements MemoryEvent {
    }
}
