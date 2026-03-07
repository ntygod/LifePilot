package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.proactive.model.NotificationType;
import com.lifepilot.agent.proactive.model.Urgency;

/**
 * SSE 通知事件载荷。
 *
 * @author zsg
 * @since 2026-03-07
 */
public record NotificationSseEvent(
        String id,
        NotificationType type,
        Urgency urgency,
        String content,
        String timestamp
) {}
