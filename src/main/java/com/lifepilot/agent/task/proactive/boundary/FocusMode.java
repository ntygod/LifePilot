package com.lifepilot.agent.task.proactive.boundary;

/**
 * 用户专注状态 — 由 {@link FocusStateDetector} 综合桌面与对话信号推断。
 *
 * <p>FOCUS_MODE 下 {@code DecisionGate} 会将 NOTIFY 降级为 QUEUE，INTERRUPT 保留，
 * 避免打扰正在专注工作的用户，同时保留真正紧急场景的打断能力。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum FocusMode {

    /** 专注态 — 全屏 / IDE / 持续高密度对话等。 */
    FOCUS_MODE,

    /** 普通态 — 默认值。 */
    NORMAL
}
