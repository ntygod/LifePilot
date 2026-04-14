package com.lifepilot.agent.task.proactive;

/**
 * 自主度级别 — 每种行为独立设置。
 *
 * <p>A（通知）：只告诉用户，不附带建议方案。
 * B（建议）：附带建议方案等用户确认。
 * C（代行）：直接执行，完成后告知用户。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum AutonomyLevel {

    /** 通知型 — 只允许 NOTIFY/QUEUE 投递。 */
    A,

    /** 建议型 — 允许通知 + 带方案。 */
    B,

    /** 代行型 — 允许执行，完成后告知。 */
    C;

    /** 该级别是否允许执行动作。 */
    public boolean canExecute() { return this == C; }

    /** 该级别是否允许附带建议方案。 */
    public boolean canSuggest() { return this == B || this == C; }
}
