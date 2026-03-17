package com.lifepilot.agent.suspend.event;

import org.springframework.lang.Nullable;

/**
 * 用户确认恢复事件 — 用户对高风险工具执行做出决定后发布。
 *
 * @author zsg
 * @since 2026-03-17
 */
public record UserConfirmationEvent(String confirmationId, boolean approved, @Nullable String reason) {}
