package com.lifepilot.notification;

/**
 * 通知紧急程度枚举。
 *
 * <p>从 {@code com.lifepilot.agent.proactive.model.Urgency} 迁移至独立通知模块，
 * 供所有需要通知能力的模块统一引用。
 *
 * @author zsg
 * @since 2026-03-13
 */
public enum Urgency {
    /** 高紧急度：需要立即关注。 */
    HIGH,
    /** 中紧急度：需要关注但不紧急。 */
    MEDIUM,
    /** 低紧急度：可以稍后处理。 */
    LOW
}
