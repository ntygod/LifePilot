package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.llm.multimodal.MediaContent;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ReAct Agent 不可变状态快照。
 *
 * <p>替代旧 {@code AgentState}，通过 {@code toBuilder()} 派生新实例，
 * 避免在循环过程中直接修改共享状态。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
@Builder(toBuilder = true)
public record ReactAgentState(
        String traceId,
        String sessionId,
        @Nullable String turnId,
        String goal,
        InteractionSource source,
        @Nullable String userId,
        AgentTaskMode taskMode,
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
        @Nullable CompletionReason completionReason,
        @Nullable String reasoningSummary,
        CompletionMode completionMode,
        @Nullable List<String> allowedToolIds,
        /** 已被 Skill 激活的工具 ID 集合 — 加载 Skill 时动态扩充。 */
        @Nullable Set<String> activatedToolIds,
        @Nullable List<MediaContent> pendingMedia,
        int earlyStopRejectCount,
        boolean suspended,
        @Nullable SuspendReason suspendReason
) {

    public ReactAgentState {
        source = source != null ? source : InteractionSource.system("unknown");
        taskMode = taskMode != null ? taskMode : AgentTaskMode.AUTO;
        steps = List.copyOf(steps);
        shortTermMemory = List.copyOf(shortTermMemory);
        mentionedEntities = List.copyOf(mentionedEntities);
        allowedToolIds = allowedToolIds != null ? List.copyOf(allowedToolIds) : null;
        activatedToolIds = activatedToolIds != null ? Set.copyOf(activatedToolIds) : null;
        pendingMedia = pendingMedia != null ? List.copyOf(pendingMedia) : null;
    }

    /**
     * 基于请求创建新的运行状态。
     *
     * @param request Agent 请求
     * @param defaultBudget 未指定预算时的默认预算
     * @return 初始状态
     */
    public static ReactAgentState init(AgentRequest request, Budget defaultBudget) {
        return ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(request.sessionId())
                .turnId(request.turnId())
                .goal(request.message())
                .source(request.source())
                .userId(request.userId())
                .taskMode(request.taskMode())
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
                .completionReason(null)
                .completionMode(CompletionMode.NORMAL)
                .allowedToolIds(request.allowedToolIds())
                .activatedToolIds(null)
                .pendingMedia(null)
                .earlyStopRejectCount(0)
                .suspended(false)
                .suspendReason(null)
                .build();
    }

    public String channel() {
        return source.sourceId();
    }

    public SourceKind sourceKind() {
        return source.sourceKind();
    }

    @Nullable
    public String channelPlatform() {
        return source.channelPlatform();
    }

    @Nullable
    public String channelInstanceId() {
        return source.channelInstanceId();
    }

    public boolean isDone() {
        return done;
    }

    /**
     * 进入挂起态。
     *
     * @param reason 挂起原因
     * @return 新状态
     */
    public ReactAgentState suspend(SuspendReason reason) {
        return this.toBuilder()
                .suspended(true)
                .suspendReason(reason)
                .build();
    }

    /**
     * 从挂起态恢复。
     *
     * @return 新状态
     */
    public ReactAgentState resume() {
        return this.toBuilder()
                .suspended(false)
                .suspendReason(null)
                .build();
    }

    /**
     * 追加一步运行步骤。
     *
     * @param step 运行步骤
     * @return 新状态
     */
    public ReactAgentState appendStep(ReactStep step) {
        var newSteps = new ArrayList<>(steps);
        newSteps.add(step);
        return this.toBuilder()
                .steps(newSteps)  // 由 record 构造函数 List.copyOf() 冻结，无需提前拷贝
                .stepCount(stepCount + 1)
                .build();
    }

    /**
     * 追加待注入媒体。
     *
     * @param media 媒体内容
     * @return 新状态
     */
    public ReactAgentState appendPendingMedia(MediaContent media) {
        var newMedia = pendingMedia != null ? new ArrayList<>(pendingMedia) : new ArrayList<MediaContent>();
        newMedia.add(media);
        return this.toBuilder()
                .pendingMedia(newMedia)  // 由 record 构造函数 List.copyOf() 冻结，无需提前拷贝
                .build();
    }

    /**
     * 清空待注入媒体缓存。
     *
     * @return 新状态
     */
    public ReactAgentState clearPendingMedia() {
        return this.toBuilder()
                .pendingMedia(null)
                .build();
    }

    /**
     * 合并新激活的工具 ID 到已有集合，返回新状态。
     *
     * @param newToolIds 新激活的工具 ID
     * @return 包含合并后激活工具集的新状态
     */
    public ReactAgentState withActivatedToolIds(Set<String> newToolIds) {
        if (newToolIds == null || newToolIds.isEmpty()) {
            return this;
        }
        var merged = new LinkedHashSet<String>();
        if (activatedToolIds != null) {
            merged.addAll(activatedToolIds);
        }
        merged.addAll(newToolIds);
        return this.toBuilder()
                .activatedToolIds(merged)
                .build();
    }

    public static class ReactAgentStateBuilder {

        public ReactAgentStateBuilder channel(String channel) {
            return source(InteractionSource.legacy(channel, this.sessionId));
        }
    }
}
