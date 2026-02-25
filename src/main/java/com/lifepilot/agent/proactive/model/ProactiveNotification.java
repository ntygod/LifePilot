package com.lifepilot.agent.proactive.model;

import java.time.Instant;

/**
 * 主动通知 — 最终发送给用户的通知。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ProactiveNotification(
        String id,
        NotificationType type,
        Urgency urgency,
        String content,
        String channel,
        String responseStatus,
        Instant sentAt
) {}
