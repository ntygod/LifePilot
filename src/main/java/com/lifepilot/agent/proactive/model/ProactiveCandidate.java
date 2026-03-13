package com.lifepilot.agent.proactive.model;

import com.lifepilot.notification.Urgency;
import org.springframework.lang.Nullable;

/**
 * 候选记录 — 表示一个值得考虑主动介入的机会。
 *
 * <p>通过 PolicyEngine 过滤后进入分发流程。typeId 为字符串标识（如 "deadline_reminder"），
 * 替代原有的 NotificationType 枚举，支持动态注册的通知类型。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ProactiveCandidate(
        String typeId,
        Urgency urgency,
        String summary,
        @Nullable String subjectId,
        InitiativeType initiativeType
) {}
