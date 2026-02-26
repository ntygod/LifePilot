package com.lifepilot.sandbox.model;

/**
 * 代码执行状态。
 *
 * @author zsg
 * @since 2026-03-01
 */
public enum ExecutionState {

    /** 正常完成。 */
    COMPLETED,

    /** 执行超时。 */
    TIMEOUT,

    /** 执行失败。 */
    FAILED
}
