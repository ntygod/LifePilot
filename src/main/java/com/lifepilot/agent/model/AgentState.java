package com.lifepilot.agent.model;

import com.lifepilot.agent.session.SessionSnapshot;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Agent 不可变状态快照。
 *
 * <p>每次状态转换生成新实例，旧实例保持不变。
 * 使用 {@code toBuilder()} 模式实现部分字段更新。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@Builder(toBuilder = true)
public record AgentState(
        String traceId,
        String sessionId,
        String goal,
        AgentPhase phase,
        String channel,
        List<StepRecord> steps,
        int stepCount,
        @Nullable ExecutionPlan plan,
        int planStepIndex,
        int revisionCount,
        List<String> shortTermMemory,
        List<String> mentionedEntities,
        Budget budget,
        @Nullable String parentTraceId,
        int depth,
        boolean done,
        @Nullable String finalOutput,
        @Nullable String terminationReason,
        /**
         * 本轮推理概要摘要。
         *
         * <p>用于会话快照（recentTurns）与前端消息级「推理过程」折叠面板展示，
         * 由 AgentLoop 在循环结束时基于步骤数 / Token 使用等信息生成。</p>
         */
        @Nullable String reasoningSummary,
        /** 工具白名单 — 限制当前 Agent 可使用的工具 ID 列表，null 表示不限制。 */
        @Nullable List<String> allowedToolIds
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public AgentState {
        steps = List.copyOf(steps);
        shortTermMemory = List.copyOf(shortTermMemory);
        mentionedEntities = List.copyOf(mentionedEntities);
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
    }

    /**
     * 创建初始状态。
     *
     * @param request 用户请求
     * @return 初始 AgentState
     */
    public static AgentState init(AgentRequest request) {
        return AgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(request.sessionId())
                .goal(request.message())
                .phase(AgentPhase.UNDERSTANDING)
                .channel(request.channel())
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(request.budget() != null ? request.budget() : Budget.defaultBudget())
                .parentTraceId(request.parentTraceId())
                .depth(request.depth())
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .allowedToolIds(request.allowedToolIds())
                .build();
    }

    /**
     * 从会话快照恢复上下文。
     *
     * @param session 会话快照
     * @param request 用户请求
     * @return 恢复后的 AgentState
     */
    public static AgentState fromSession(SessionSnapshot session, AgentRequest request) {
        return AgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(session.sessionId())
                .goal(request.message())
                .phase(AgentPhase.UNDERSTANDING)
                .channel(session.channelId())
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(session.mentionedEntities())
                .budget(request.budget() != null ? request.budget() : Budget.defaultBudget())
                .parentTraceId(request.parentTraceId())
                .depth(request.depth())
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .allowedToolIds(request.allowedToolIds())
                .build();
    }

    /**
     * 创建 SubAgent 子状态。
     *
     * @param parentState 父状态
     * @param subGoal     子目标
     * @param subBudget   子预算
     * @return 子 AgentState
     */
    public static AgentState forSubAgent(AgentState parentState, String subGoal, Budget subBudget) {
        return AgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(parentState.sessionId())
                .goal(subGoal)
                .phase(AgentPhase.UNDERSTANDING)
                .channel(parentState.channel())
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(subBudget)
                .parentTraceId(parentState.traceId())
                .depth(parentState.depth() + 1)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .allowedToolIds(null)
                .build();
    }

    /** 检查是否已完成。 */
    public boolean isDone() {
        return done;
    }

    /** 将最终状态转换为 AgentResponse。 */
    public AgentResponse toResponse() {
        return new AgentResponse(
                traceId, sessionId,
                finalOutput != null ? finalOutput : "",
                budget.tokensUsed(), stepCount, terminationReason,
                null,
                null,
                null
        );
    }
}
