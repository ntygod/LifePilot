package com.lifepilot.meta.infra.interaction;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 交互请求 — 描述一次用户交互的完整信息。
 *
 * @param interactionId 交互唯一标识（UUID）
 * @param type          交互类型
 * @param sessionId     当前会话 ID，用于定位 SSE emitter
 * @param message       提示消息
 * @param options       CHOOSE 类型的选项列表（其他类型为 null）
 * @author zsg
 * @since 2026-03-08
 */
public record InteractionRequest(
        String interactionId,
        InteractionType type,
        String sessionId,
        String message,
        @Nullable List<String> options
) {}
