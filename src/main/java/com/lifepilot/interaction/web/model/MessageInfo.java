package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.CompletionMode;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 消息摘要信息。
 *
 * @param id 消息 ID
 * @param turnId 关联 turnId
 * @param role 角色（user / assistant / permission-approval）
 * @param content 文本内容
 * @param a2uiComponents A2UI 组件树
 * @param timestamp 消息时间戳
 * @param reasoningSummary 推理摘要（ReAct 过程摘要）
 * @param traceId 关联 traceId
 * @param attachments 附件信息
 * @param singleTurnOverride 用户发送本轮时的临时上下文/模型覆盖
 * @param sources 本轮回答参考的知识库 / 记忆来源
 * @param memoryChanges 本轮对话沉淀的记忆摘要
 * @param toolsSummary 工具/技能执行摘要
 * @param reactSteps ReAct 步骤序列
 * @param completionMode 完成模式
 * @param resumedFromTraceId 恢复来源 traceId
 * @param turnStatus 轮次状态
 * @param errorMessage 轮次错误信息
 * @param taskRecovery 任务恢复摘要
 * @param turnRecoveryContext 当前轮次恢复上下文
 * @param executionConstraints 本轮执行约束摘要
 * @param reasoningContent 推理过程文本（DeepSeek/Qwen 等推理模型 reasoning_content；
 *                        历史会话进入时供前端 ReasoningSection 渲染折叠区域）
 * @param reasoningDurationMs 推理过程持续时间（毫秒），暂留扩展位
 * @param knowledgeSettlements 已沉淀到资料库的消息/产物记录
 * @author zsg
 * @since 2026-03-25
 */
public record MessageInfo(
        String id,
        @Nullable String turnId,
        String role,
        String content,
        @Nullable List<A2uiComponent> a2uiComponents,
        Instant timestamp,
        @Nullable String reasoningSummary,
        @Nullable String traceId,
        @Nullable List<AttachmentInfo> attachments,
        @Nullable SessionConfigOverride singleTurnOverride,
        @Nullable List<Map<String, Object>> sources,
        @Nullable List<Map<String, Object>> memoryChanges,
        @Nullable List<Map<String, Object>> toolsSummary,
        @Nullable List<Map<String, Object>> reactSteps,
        @Nullable CompletionMode completionMode,
        @Nullable String resumedFromTraceId,
        @Nullable ChatTurnStatus turnStatus,
        @Nullable String errorMessage,
        @Nullable Map<String, Object> taskRecovery,
        @Nullable Map<String, Object> turnRecoveryContext,
        @Nullable Map<String, Object> executionConstraints,
        @Nullable String reasoningContent,
        @Nullable Long reasoningDurationMs,
        @Nullable List<Map<String, Object>> knowledgeSettlements,
        @Nullable List<ArtifactRefInfo> artifactRefs
) {
    public MessageInfo(
            String id,
            @Nullable String turnId,
            String role,
            String content,
            @Nullable List<A2uiComponent> a2uiComponents,
            Instant timestamp,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            @Nullable List<AttachmentInfo> attachments,
            @Nullable List<Map<String, Object>> memoryChanges,
            @Nullable List<Map<String, Object>> toolsSummary,
            @Nullable List<Map<String, Object>> reactSteps,
            @Nullable CompletionMode completionMode,
            @Nullable String resumedFromTraceId,
            @Nullable ChatTurnStatus turnStatus,
            @Nullable String errorMessage,
            @Nullable String reasoningContent,
            @Nullable Long reasoningDurationMs,
            @Nullable List<Map<String, Object>> knowledgeSettlements,
            @Nullable List<ArtifactRefInfo> artifactRefs
    ) {
        this(id, turnId, role, content, a2uiComponents, timestamp, reasoningSummary, traceId,
                attachments, null, null, memoryChanges, toolsSummary, reactSteps, completionMode, resumedFromTraceId, turnStatus, errorMessage,
                null, null, null, reasoningContent, reasoningDurationMs, knowledgeSettlements, artifactRefs);
    }

    public MessageInfo(
            String id,
            @Nullable String turnId,
            String role,
            String content,
            @Nullable List<A2uiComponent> a2uiComponents,
            Instant timestamp,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            @Nullable List<AttachmentInfo> attachments,
            @Nullable List<Map<String, Object>> sources,
            @Nullable List<Map<String, Object>> memoryChanges,
            @Nullable List<Map<String, Object>> toolsSummary,
            @Nullable List<Map<String, Object>> reactSteps,
            @Nullable CompletionMode completionMode,
            @Nullable String resumedFromTraceId,
            @Nullable ChatTurnStatus turnStatus,
            @Nullable String errorMessage,
            @Nullable String reasoningContent,
            @Nullable Long reasoningDurationMs,
            @Nullable List<Map<String, Object>> knowledgeSettlements,
            @Nullable List<ArtifactRefInfo> artifactRefs
    ) {
        this(id, turnId, role, content, a2uiComponents, timestamp, reasoningSummary, traceId,
                attachments, null, sources, memoryChanges, toolsSummary, reactSteps, completionMode, resumedFromTraceId, turnStatus, errorMessage,
                null, null, null, reasoningContent, reasoningDurationMs, knowledgeSettlements, artifactRefs);
    }

    public MessageInfo(
            String id,
            String role,
            String content,
            @Nullable List<A2uiComponent> a2uiComponents,
            Instant timestamp,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            @Nullable List<AttachmentInfo> attachments,
            @Nullable List<Map<String, Object>> memoryChanges,
            @Nullable List<Map<String, Object>> toolsSummary,
            @Nullable List<Map<String, Object>> reactSteps,
            @Nullable CompletionMode completionMode,
            @Nullable String resumedFromTraceId
    ) {
        this(id, null, role, content, a2uiComponents, timestamp, reasoningSummary, traceId,
                attachments, null, null, memoryChanges, toolsSummary, reactSteps, completionMode, resumedFromTraceId, null, null, null, null, null, null, null, null, null);
    }

    public MessageInfo(
            String id,
            String role,
            String content,
            @Nullable List<A2uiComponent> a2uiComponents,
            Instant timestamp,
            @Nullable String reasoningSummary,
            @Nullable String traceId,
            @Nullable List<AttachmentInfo> attachments,
            @Nullable List<Map<String, Object>> reactSteps,
            @Nullable CompletionMode completionMode,
            @Nullable String resumedFromTraceId
    ) {
        this(id, role, content, a2uiComponents, timestamp, reasoningSummary, traceId,
                attachments, null, null, reactSteps, completionMode, resumedFromTraceId);
    }
}
