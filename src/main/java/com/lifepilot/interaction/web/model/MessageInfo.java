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
 * @param role 角色（user / assistant / tool-confirmation）
 * @param content 文本内容
 * @param a2uiComponents A2UI 组件树
 * @param timestamp 消息时间戳
 * @param reasoningSummary 推理摘要
 * @param traceId 关联 traceId
 * @param attachments 附件信息
 * @param reactSteps ReAct 步骤序列
 * @param completionMode 完成模式
 * @param resumedFromTraceId 恢复来源 traceId
 * @author zsg
 * @since 2026-02-27
 */
public record MessageInfo(
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
) {}
