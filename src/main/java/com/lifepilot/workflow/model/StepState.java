package com.lifepilot.workflow.model;

/**
 * 工作流步骤执行状态枚举。
 *
 * <p>定义单个步骤在执行过程中的所有可能状态。
 *
 * @author zsg
 * @since 2026-02-26
 */
public enum StepState {

    /** 待执行。 */
    PENDING,

    /** 正在执行中。 */
    RUNNING,

    /** 执行成功完成。 */
    COMPLETED,

    /** 执行失败。 */
    FAILED,

    /** 已跳过（由 ErrorStrategy.Skip 触发）。 */
    SKIPPED
}
