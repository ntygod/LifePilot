package com.lifepilot.meta.infra.interaction;

/**
 * 交互类型枚举。
 *
 * @author zsg
 * @since 2026-03-08
 */
public enum InteractionType {

    /** 确认操作（yes/no）。 */
    CONFIRM,

    /** 选择操作（从选项列表中选择）。 */
    CHOOSE,

    /** 输入操作（自由文本输入）。 */
    INPUT,

    /** 通知操作（非阻塞，仅推送消息）。 */
    NOTIFY
}
