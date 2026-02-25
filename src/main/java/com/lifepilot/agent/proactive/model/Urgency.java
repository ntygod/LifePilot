package com.lifepilot.agent.proactive.model;

/**
 * 紧急程度枚举。
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum Urgency {
    /** 高紧急度：需要立即关注。 */
    HIGH,
    /** 中紧急度：需要关注但不紧急。 */
    MEDIUM,
    /** 低紧急度：可以稍后处理。 */
    LOW
}
