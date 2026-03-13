package com.lifepilot.agent.proactive.model;

import com.lifepilot.notification.Urgency;

import java.time.Instant;

/**
 * 主动通知 — 最终发送给用户的通知。
 *
 * <p>typeId 为字符串标识（如 "deadline_reminder"），替代原有的 NotificationType 枚举。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ProactiveNotification(
        String id,
        String typeId,
        Urgency urgency,
        String content,
        String channel,
        String responseStatus,
        Instant sentAt
) {}
