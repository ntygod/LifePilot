package com.lifepilot.interaction.web.model;

/**
 * SSE 事件封装。
 *
 * @param eventType 事件类型（token / ui / done / error）
 * @param data      事件数据
 * @author zsg
 * @since 2026-02-27
 */
public record SseEvent(
        String eventType,
        Object data
) {}
