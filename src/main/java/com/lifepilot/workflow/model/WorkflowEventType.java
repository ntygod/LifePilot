package com.lifepilot.workflow.model;

/**
 * 工作流审计事件类型枚举。
 *
 * @author zsg
 * @since 2026-03-09
 */
public enum WorkflowEventType {

    /** 工作流实例创建。 */
    INSTANCE_CREATED,

    /** 工作流实例状态变更。 */
    INSTANCE_STATE_CHANGED,

    /** 步骤开始执行。 */
    STEP_STARTED,

    /** 步骤执行成功。 */
    STEP_COMPLETED,

    /** 步骤执行失败。 */
    STEP_FAILED,

    /** 步骤被跳过。 */
    STEP_SKIPPED,

    /** 审批请求发起。 */
    APPROVAL_REQUESTED,

    /** 审批决策完成。 */
    APPROVAL_DECIDED,

    /** 步骤重试（每次重试时记录）。 */
    STEP_RETRIED,

    /** 步骤重试耗尽。 */
    STEP_RETRY_EXHAUSTED
}
