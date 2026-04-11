package com.lifepilot.interaction.web.model;

/**
 * 前端 Trace 列表项 DTO（与 zhiwei-web 的 types/TraceItem 对齐）。
 *
 * @param id          traceId
 * @param sessionId   会话 ID
 * @param userMessage 用户输入（若配置为不落盘内容，则为占位符）
 * @param success     是否成功
 * @param totalSteps  总步骤数
 * @param totalTokens 总 Token 数
 * @param durationMs  总耗时（毫秒）
 * @param createdAt   创建时间（ISO 8601）
 * @author zsg
 * @since 2026-03-05
 */
public record TraceItemDto(
        String id,
        String sessionId,
        String userMessage,
        boolean success,
        int totalSteps,
        int totalTokens,
        long durationMs,
        String createdAt
) {
}

