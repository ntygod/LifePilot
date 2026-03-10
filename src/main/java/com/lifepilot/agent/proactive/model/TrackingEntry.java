package com.lifepilot.agent.proactive.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 追踪条目 — ResponseTracker 的待追踪通知。
 *
 * <p>typeId 为字符串标识，替代原有的 NotificationType 枚举。
 * subjectId 为可选的主体标识，支持对象级别的频率控制。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TrackingEntry(
        String typeId,
        @Nullable String subjectId,
        Instant sentAt
) {}
