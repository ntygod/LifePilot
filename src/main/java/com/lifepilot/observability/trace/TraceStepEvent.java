package com.lifepilot.observability.trace;

/**
 * Trace 步骤事件（用于实时订阅/流式推送）。
 *
 * @param traceId 追踪 ID
 * @param step    追踪步骤
 * @author zsg
 * @since 2026-03-05
 */
public record TraceStepEvent(
        String traceId,
        TraceStep step
) {
}

