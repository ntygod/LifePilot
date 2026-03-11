package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 消息摘要信息。
 *
 * @param id               消息 ID
 * @param role             角色（user / assistant）
 * @param content          文本内容
 * @param a2ui             A2UI 组件树（可为 null）
 * @param timestamp        消息时间戳
 * @param reasoningSummary 本条消息对应一轮对话的推理概要（可为 null，仅 assistant 消息返回）
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
        @Nullable String traceId
) {}
