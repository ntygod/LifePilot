package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.SuspendReason;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 挂起状态快照 record — 持久化到 suspended_agents 表。
 *
 * <p>包含 Agent 挂起时的完整状态快照，支持序列化/反序列化往返。
 * 通过 {@link #from(ReactAgentState, ObjectMapper)} 从运行时状态构建，
 * 通过 {@link #toAgentState(ObjectMapper)} 恢复为运行时状态。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@Builder(toBuilder = true)
public record SuspendedAgent(
        String traceId,
        String sessionId,
        String channel,
        SuspendReason suspendReason,
        String stateJson,
        Instant suspendedAt,
        @Nullable String streamId,
        @Nullable String budgetJson
) {

    /**
     * 从 ReactAgentState 构建挂起快照。
     *
     * <p>将完整状态序列化为 JSON，Budget 单独序列化以便恢复时精确还原预算。</p>
     *
     * @param state        当前 Agent 状态
     * @param objectMapper 序列化用 ObjectMapper（由 Spring 容器管理）
     * @return 挂起快照
     * @throws IllegalStateException 序列化失败时抛出
     */
    public static SuspendedAgent from(ReactAgentState state, ObjectMapper objectMapper) {
        try {
            String stateJson = objectMapper.writeValueAsString(state);
            String budgetJson = state.budget() != null
                    ? objectMapper.writeValueAsString(state.budget())
                    : null;

            return SuspendedAgent.builder()
                    .traceId(state.traceId())
                    .sessionId(state.sessionId())
                    .channel(state.channel())
                    .suspendReason(state.suspendReason())
                    .stateJson(stateJson)
                    .suspendedAt(Instant.now())
                    .budgetJson(budgetJson)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 ReactAgentState 失败", e);
        }
    }

    /**
     * 反序列化恢复 ReactAgentState。
     *
     * @param objectMapper 反序列化用 ObjectMapper（由 Spring 容器管理）
     * @return 恢复后的 Agent 状态
     * @throws IllegalStateException 反序列化失败时抛出
     */
    public ReactAgentState toAgentState(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(stateJson, ReactAgentState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 ReactAgentState 失败", e);
        }
    }
}
