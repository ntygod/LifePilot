package com.lifepilot.agent.proactive.model;

/**
 * 候选提醒 — 通过 RuleEngine 过滤后进入 LLM 评估的提醒候选。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ProactiveCandidate(
        NotificationType type,
        Urgency urgency,
        String reason
) {}
