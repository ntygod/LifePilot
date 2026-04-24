package com.lifepilot.tool.pipeline;

import java.time.Instant;

/**
 * 工具调用成功事件 —— ToolExecutionPipeline 在执行完成后发布。
 *
 * @param toolId 工具 ID
 * @param sessionId 会话 ID
 * @param success 是否成功
 * @param durationMs 耗时
 * @param at 时间戳
 * @author zsg
 * @since 2026-04-23
 */
public record ToolInvocationEvent(
        String toolId,
        String sessionId,
        boolean success,
        long durationMs,
        Instant at
) {}
