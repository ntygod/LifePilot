package com.lifepilot.agent.proactive.model;

import java.time.Instant;

/**
 * 追踪条目 — ResponseTracker 的待追踪通知。
 *
 * <p>typeId 为字符串标识，替代原有的 NotificationType 枚举。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TrackingEntry(
        String typeId,
        Instant sentAt
) {}
