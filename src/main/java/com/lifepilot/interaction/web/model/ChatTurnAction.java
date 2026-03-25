package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.ResumePolicy;

/**
 * 会话轮次执行动作。
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum ChatTurnAction {
    SEND,
    RETRY,
    RESUME,
    RESTART;

    public ResumePolicy toResumePolicy() {
        return this == RESTART ? ResumePolicy.FRESH : ResumePolicy.AUTO;
    }
}
