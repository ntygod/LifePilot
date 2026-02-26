package com.lifepilot.sandbox.model;

/**
 * 沙箱实例状态。
 *
 * @author zsg
 * @since 2026-03-01
 */
public enum SandboxState {

    /** 就绪，可接受执行请求。 */
    READY,

    /** 正在执行代码。 */
    RUNNING,

    /** 已关闭，不可再使用。 */
    SHUTDOWN
}
