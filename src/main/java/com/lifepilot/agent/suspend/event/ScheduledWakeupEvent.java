package com.lifepilot.agent.suspend.event;

import java.time.Instant;

/**
 * 定时唤醒恢复事件 — 延迟任务到时后发布。
 *
 * @author zsg
 * @since 2026-03-17
 */
public record ScheduledWakeupEvent(String traceId, Instant actualWakeupAt) {}
