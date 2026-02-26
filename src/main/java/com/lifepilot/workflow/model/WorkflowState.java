package com.lifepilot.workflow.model;

/**
 * 工作流实例状态枚举。
 *
 * <p>定义工作流实例在生命周期中的所有可能状态，
 * 状态机转换规则由 {@code WorkflowEngine} 强制执行。
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum WorkflowState {

    /** 已创建，尚未开始执行。 */
    CREATED,

    /** 正在执行中。 */
    RUNNING,

    /** 已暂停，等待用户恢复。 */
    PAUSED,

    /** 等待中（如 WaitStep 触发的定时等待）。 */
    WAITING,

    /** 所有步骤执行成功，工作流已完成。 */
    COMPLETED,

    /** 执行失败，已终止。 */
    FAILED,

    /** 已被用户取消。 */
    CANCELLED
}
