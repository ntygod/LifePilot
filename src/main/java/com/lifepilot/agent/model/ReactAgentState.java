package com.lifepilot.agent.model;

import com.lifepilot.agent.session.SessionSnapshot;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ReAct Agent 不可变状态快照。
 *
 * <p>替代原 {@code AgentState}，移除 {@code phase} / {@code plan} /
 * {@code planStepIndex} / {@code revisionCount} 等六阶段专属字段。
 * 每次状态变更通过 {@code toBuilder()} 生成新实例，保证不可变性。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
@Builder(toBuilder = true)
public record ReactAgentState(
        String traceId,
        String sessionId,
        String goal,
        String channel,
        List<ReactStep> steps,
        int stepCount,
        List<String> shortTermMemory,
        List<String> mentionedEntities,
        Budget budget,
        @Nullable String parentTraceId,
        int depth,
        boolean done,
        @Nullable String finalOutput,
        @Nullable String terminationReason,
        @Nullable String reasoningSummary,
        @Nullable List<String> allowedToolIds
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public ReactAgentState {
        steps = List.copyOf(steps);
        shortTermMemory = List.copyOf(shortTermMemory);
        mentionedEntities = List.copyOf(mentionedEntities);
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
    }

    /**
     * 从 AgentRequest 初始化新状态。
     *
     * @param request Agent 请求
     * @return 初始状态
     */
    public static ReactAgentState init(AgentRequest request) {
        return ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(request.sessionId())
                .goal(request.message())
                .channel(request.channel())
                .steps(List.of())
                .stepCount(0)
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
     * 从已有会话快照恢复状态。
     *
     * @param session 会话快照
     * @param request 当前请求
     * @return 恢复后的状态
     */
    public static ReactAgentState fromSession(SessionSnapshot session, AgentRequest request) {
        return ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(session.sessionId())
                .goal(request.message())
                .channel(session.channelId())
                .steps(List.of())
                .stepCount(0)
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

    /** 检查是否已完成。 */
    public boolean isDone() {
        return done;
    }

    /**
     * 追加步骤，返回新实例。
     *
     * @param step 要追加的步骤
     * @return 包含新步骤的新状态实例
     */
    public ReactAgentState appendStep(ReactStep step) {
        var newSteps = new ArrayList<>(steps);
        newSteps.add(step);
        return this.toBuilder()
                .steps(List.copyOf(newSteps))
                .stepCount(stepCount + 1)
                .build();
    }
}
