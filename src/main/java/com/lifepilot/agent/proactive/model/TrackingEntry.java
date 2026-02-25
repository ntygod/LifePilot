package com.lifepilot.agent.proactive.model;

import java.time.Instant;

/**
 * 追踪条目 — ResponseTracker 的待追踪通知。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TrackingEntry(
        NotificationType type,
        Instant sentAt
) {}
