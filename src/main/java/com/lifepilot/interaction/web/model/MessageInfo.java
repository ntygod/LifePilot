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
 * @param reasoningSummary 推理摘要
 * @param traceId 关联 traceId
 * @param attachments 附件信息
 * @param reactSteps ReAct 步骤序列
 * @param completionMode 完成模式
 * @param resumedFromTraceId 恢复来源 traceId
 * @param turnStatus 轮次状态
 * @param errorMessage 轮次错误信息
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
        @Nullable List<Map<String, Object>> reactSteps,
        @Nullable CompletionMode completionMode,
        @Nullable String resumedFromTraceId,
        @Nullable ChatTurnStatus turnStatus,
        @Nullable String errorMessage
) {
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
        this(id, null, role, content, a2uiComponents, timestamp, reasoningSummary, traceId,
                attachments, reactSteps, completionMode, resumedFromTraceId, null, null);
    }
}
