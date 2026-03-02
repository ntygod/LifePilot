package com.lifepilot.interaction.web.model;

/**
 * SSE 事件封装。
 *
 * <p>事件类型定义参见 {@link com.lifepilot.interaction.web.sse.SseEventType} 常量类。</p>
 *
 * <p>Chat 模块事件类型：{@link com.lifepilot.interaction.web.sse.SseEventType#TOKEN TOKEN},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#UI UI},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#DONE DONE},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#ERROR ERROR},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#HEARTBEAT HEARTBEAT}</p>
 *
 * <p>A2A 模块事件类型：{@link com.lifepilot.interaction.web.sse.SseEventType#TASK_STATUS_UPDATE TASK_STATUS_UPDATE},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#TASK_ARTIFACT_UPDATE TASK_ARTIFACT_UPDATE},
 * {@link com.lifepilot.interaction.web.sse.SseEventType#TASK_COMPLETE TASK_COMPLETE}</p>
 *
 * @param eventType 事件类型（应使用 {@link com.lifepilot.interaction.web.sse.SseEventType} 常量）
 * @param data      事件数据
 * @author zsg
 * @since 2026-02-27
 */
public record SseEvent(
        String eventType,
        Object data
) {}
