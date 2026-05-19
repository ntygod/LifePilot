package com.lifepilot.agent.initiative.execute;

import com.lifepilot.agent.initiative.model.Thought;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动执行结果。
 *
 * @author zsg
 * @since 2026-06-01
 */
public record ExecutionResult(
    String thoughtId,
    Status status,
    @Nullable String resultSummary,
    @Nullable String traceId,
    Instant completedAt
) {
    public enum Status {
        COMPLETED,
        FAILED,
        DEGRADED_TO_CONVERSATION,
        NO_PERMISSION
    }

    public static ExecutionResult completed(Thought thought, String summary, String traceId) {
        return new ExecutionResult(thought.id(), Status.COMPLETED, summary, traceId, Instant.now());
    }

    public static ExecutionResult failed(Thought thought, String error) {
        return new ExecutionResult(thought.id(), Status.FAILED, error, null, Instant.now());
    }

    public static ExecutionResult degradedToConversation(Thought thought) {
        return new ExecutionResult(thought.id(), Status.DEGRADED_TO_CONVERSATION,
                "无授权，降级为对话模式", null, Instant.now());
    }

    public static ExecutionResult noPermission(Thought thought) {
        return new ExecutionResult(thought.id(), Status.NO_PERMISSION,
                "未找到匹配的执行授权", null, Instant.now());
    }
}
