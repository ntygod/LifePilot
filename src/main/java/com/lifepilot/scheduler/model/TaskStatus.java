package com.lifepilot.scheduler.model;

/**
 * 定时任务状态枚举。
 *
 * @author zsg
 * @since 2026-03-16
 */
public enum TaskStatus {
    /** 等待触发。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 已完成（一次性任务执行成功）。 */
    COMPLETED,
    /** 已取消。 */
    CANCELLED,
    /** 执行失败。 */
    FAILED
}
