package com.lifepilot.scheduler.model;

/**
 * 定时任务触发类型枚举。
 *
 * @author zsg
 * @since 2026-03-16
 */
public enum TriggerType {
    /** 一次性触发。 */
    ONCE,
    /** 基于 cron 表达式的周期性触发。 */
    CRON
}
