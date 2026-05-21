package com.lifepilot.agent.initiative.model;

/**
 * 想法生命周期状态。
 *
 * @author zsg
 * @since 2026-06-01
 */
public enum ThoughtState {
    /** 酝酿中：刚产生，还不够成熟。 */
    BREWING,
    /** 就绪：已成熟，等待合适时机表达。 */
    READY,
    /** 已表达：已发起对话。 */
    EXPRESSED,
    /** 已放弃：过期、被新信息否定、或用户明确拒绝。 */
    DISMISSED,
    /** 已吸收：用户采纳并展开了对话。 */
    ABSORBED;

    public boolean isActive() {
        return this == BREWING || this == READY;
    }

    public boolean isTerminal() {
        return this == DISMISSED || this == ABSORBED;
    }
}
