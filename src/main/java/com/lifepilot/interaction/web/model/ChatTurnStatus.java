package com.lifepilot.interaction.web.model;

/**
 * 会话轮次状态。
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum ChatTurnStatus {
    PENDING,
    SUCCESS,
    FAILED,
    DEGRADED,
    SUSPENDED,
    CANCELLED
}
