package com.lifepilot.agent.suspend.event;

import org.springframework.lang.Nullable;

/**
 * 浏览器人工接管完成事件 — 用户在浏览器会话中完成登录/验证码/人机验证后发布。
 *
 * <p>监听方 {@link com.lifepilot.agent.suspend.AgentResumeListener} 会根据
 * {@code sessionId} 匹配挂起的 Agent，转成 {@link com.lifepilot.agent.suspend.model.ResumePayload.BrowserTakeoverCompleted}
 * 并调用 orchestrator 恢复。{@code cancelled=true} 表示用户放弃任务，note 透传到 payload。</p>
 *
 * @param sessionId 浏览器会话 ID，用于匹配 SuspendReason.BrowserTakeover
 * @param cancelled 用户是否取消任务
 * @param note      用户备注（可空）
 * @author zsg
 * @since 2026-04-24
 */
public record BrowserTakeoverCompletedEvent(
        String sessionId,
        boolean cancelled,
        @Nullable String note
) {}
