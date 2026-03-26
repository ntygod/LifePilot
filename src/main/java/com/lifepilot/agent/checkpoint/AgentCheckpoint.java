package com.lifepilot.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 失败检查点。
 *
 * @author zsg
 * @since 2026-03-21
 */
public record AgentCheckpoint(
        String sessionId,
        String channel,
        String taskFingerprint,
        String sourceTraceId,
        String stateJson,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 从当前状态构建检查点。
     */
    public static AgentCheckpoint from(ReactAgentState state,
                                       String taskFingerprint,
                                       ObjectMapper objectMapper) {
        Instant now = Instant.now();
        return new AgentCheckpoint(
                state.sessionId(),
                state.channel(),
                taskFingerprint,
                state.traceId(),
                serializeState(state, objectMapper),
                state.terminationReason(),
                now,
                now
        );
    }

    /**
     * 将检查点恢复为可继续执行的 Agent 状态。
     */
    public ReactAgentState restore(ObjectMapper objectMapper, AgentRequest request) {
        try {
            ReactAgentState restored = objectMapper.readValue(stateJson, ReactAgentState.class);
            return restored.toBuilder()
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(request.sessionId())
                    .turnId(request.turnId())
                    .goal(request.message())
                    .channel(request.channel())
                    .parentTraceId(sourceTraceId)
                    .preferredProvider(request.preferredProvider())
                    .allowedToolIds(request.allowedToolIds())
                    .done(false)
                    .finalOutput(null)
                    .terminationReason(null)
                    .reasoningSummary(null)
                    .suspended(false)
                    .suspendReason(null)
                    .resumedFromTraceId(sourceTraceId)
                    .completionMode(CompletionMode.NORMAL)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 Agent 检查点失败", e);
        }
    }

    private static String serializeState(ReactAgentState state, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Agent 检查点失败", e);
        }
    }
}
