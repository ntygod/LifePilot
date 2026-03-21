package com.lifepilot.agent.model;

import com.lifepilot.agent.session.SessionSnapshot;
import com.lifepilot.llm.multimodal.MediaContent;
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
        @Nullable String resumedFromTraceId,
        int depth,
        @Nullable String preferredProvider,
        boolean done,
        @Nullable String finalOutput,
        @Nullable String terminationReason,
        @Nullable String reasoningSummary,
        CompletionMode completionMode,
        @Nullable List<String> allowedToolIds,
        @Nullable List<MediaContent> pendingMedia,
        boolean suspended,
        @Nullable SuspendReason suspendReason
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public ReactAgentState {
        steps = List.copyOf(steps);
        shortTermMemory = List.copyOf(shortTermMemory);
        mentionedEntities = List.copyOf(mentionedEntities);
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
        pendingMedia = pendingMedia != null ? List.copyOf(pendingMedia) : null;
    }

    /**
     * 从 AgentRequest 初始化新状态。
     *
     * @param request       Agent 请求
     * @param defaultBudget 请求未指定预算时的默认预算
     * @return 初始状态
     */
    public static ReactAgentState init(AgentRequest request, Budget defaultBudget) {
        return ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(request.sessionId())
                .goal(request.message())
                .channel(request.channel())
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(request.budget() != null ? request.budget() : defaultBudget)
                .parentTraceId(request.parentTraceId())
                .resumedFromTraceId(null)
                .depth(request.depth())
                .preferredProvider(request.preferredProvider())
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .completionMode(CompletionMode.NORMAL)
                .allowedToolIds(request.allowedToolIds())
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();
    }

    /**
     * 从已有会话快照恢复状态。
     *
     * @param session       会话快照
     * @param request       当前请求
     * @param defaultBudget 请求未指定预算时的默认预算
     * @return 恢复后的状态
     */
    public static ReactAgentState fromSession(SessionSnapshot session, AgentRequest request, Budget defaultBudget) {
        return ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(session.sessionId())
                .goal(request.message())
                .channel(session.channelId())
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(session.mentionedEntities())
                .budget(request.budget() != null ? request.budget() : defaultBudget)
                .parentTraceId(request.parentTraceId())
                .resumedFromTraceId(null)
                .depth(request.depth())
                .preferredProvider(request.preferredProvider())
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .completionMode(CompletionMode.NORMAL)
                .allowedToolIds(request.allowedToolIds())
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();
    }

    /** 检查是否已完成。 */
    public boolean isDone() {
        return done;
    }

    /**
     * 进入挂起态，返回新实例。
     *
     * @param reason 挂起原因
     * @return suspended=true 且 suspendReason 已设置的新状态实例
     */
    public ReactAgentState suspend(SuspendReason reason) {
        return this.toBuilder()
                .suspended(true)
                .suspendReason(reason)
                .build();
    }

    /**
     * 从挂起态恢复，返回新实例。
     *
     * @return suspended=false 且 suspendReason=null 的新状态实例
     */
    public ReactAgentState resume() {
        return this.toBuilder()
                .suspended(false)
                .suspendReason(null)
                .build();
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

    /**
     * 追加待注入媒体内容，返回新实例。
     *
     * @param media 要追加的媒体内容
     * @return 包含新媒体的新状态实例
     */
    public ReactAgentState appendPendingMedia(MediaContent media) {
        var newMedia = pendingMedia != null ? new ArrayList<>(pendingMedia) : new ArrayList<MediaContent>();
        newMedia.add(media);
        return this.toBuilder()
                .pendingMedia(List.copyOf(newMedia))
                .build();
    }

    /**
     * 清除待注入媒体缓冲区，返回新实例。
     *
     * @return pendingMedia 为 null 的新状态实例
     */
    public ReactAgentState clearPendingMedia() {
        return this.toBuilder()
                .pendingMedia(null)
                .build();
    }
}
