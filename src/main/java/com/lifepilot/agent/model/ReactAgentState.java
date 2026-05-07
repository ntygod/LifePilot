package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.llm.multimodal.MediaContent;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        /** 当前会话通过 {@code tool.search} 发现并已暴露给 LLM 的工具 ID 集合。 */
        @Nullable Set<String> discoveredToolIds,
        /** 已加载的 Skill 指南内容 — 注入系统提示词供 LLM 遵循。 */
        @Nullable String loadedSkillContent,
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
        discoveredToolIds = discoveredToolIds != null ? Set.copyOf(discoveredToolIds) : null;
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
                .discoveredToolIds(null)
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
     * 合并新发现的工具 ID 到已有集合，返回新状态。
     *
     * @param newToolIds 新发现的工具 ID
     * @return 包含合并后发现工具集的新状态
     */
    public ReactAgentState withDiscoveredToolIds(Set<String> newToolIds) {
        if (newToolIds == null || newToolIds.isEmpty()) {
            return this;
        }
        var merged = new LinkedHashSet<String>();
        if (discoveredToolIds != null) {
            merged.addAll(discoveredToolIds);
        }
        merged.addAll(newToolIds);
        return this.toBuilder()
                .discoveredToolIds(merged)
                .build();
    }

    /** loadedSkillContent 硬上限（20KB），超限时从头部裁掉以保留最新加载的 skill 指南。 */
    private static final int MAX_SKILL_CONTENT_CHARS = 20 * 1024;

    /** 已加载内容中提取 {@code <skill name="X">} 的 skill 名称，用于去重。 */
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("<skill\\s+name=\"([^\"]+)\"");

    /**
     * 追加已加载的 Skill 指南内容，累积拼接到已有内容之后。
     *
     * <p>实现两道防线防止 LLM 多轮重复 {@code skill.load(["same-skill"])} 撑爆上下文：</p>
     * <ol>
     *   <li>去重：按 {@code <skill name="X">} 的 name 比对，已加载过的 skill 整段跳过</li>
     *   <li>硬上限：{@value #MAX_SKILL_CONTENT_CHARS} 字符，超限时从头部截断，保留最新加载的尾部内容</li>
     * </ol>
     *
     * @param newContent 新加载的 Skill 指南文本
     * @return 包含累积内容的新状态
     */
    public ReactAgentState appendSkillContent(String newContent) {
        if (newContent == null || newContent.isBlank()) {
            return this;
        }

        // 去重：若新内容中的 skill name 已在已加载内容里，整段跳过
        if (loadedSkillContent != null) {
            Matcher nameMatcher = SKILL_NAME_PATTERN.matcher(newContent);
            while (nameMatcher.find()) {
                String name = nameMatcher.group(1);
                if (loadedSkillContent.contains("<skill name=\"" + name + "\"")) {
                    return this;
                }
            }
        }

        String merged = loadedSkillContent != null
                ? loadedSkillContent + "\n\n" + newContent
                : newContent;

        // 硬上限：超限从头部截断，保留最新加载的尾部
        if (merged.length() > MAX_SKILL_CONTENT_CHARS) {
            merged = "...[已截断更早的 skill 指南]...\n\n"
                    + merged.substring(merged.length() - MAX_SKILL_CONTENT_CHARS);
        }

        return this.toBuilder()
                .loadedSkillContent(merged)
                .build();
    }

    public static class ReactAgentStateBuilder {

        public ReactAgentStateBuilder channel(String channel) {
            return source(InteractionSource.legacy(channel, this.sessionId));
        }
    }
}
