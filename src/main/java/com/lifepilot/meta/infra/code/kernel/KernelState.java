package com.lifepilot.meta.infra.code.kernel;

/**
 * 持久内核状态枚举。
 *
 * <p>描述内核从启动到关闭的完整生命周期状态。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public enum KernelState {

    /** 内核正在启动。 */
    STARTING,

    /** 内核就绪，可接受执行请求。 */
    READY,

    /** 内核正在执行代码。 */
    BUSY,

    /** 内核发生错误（如进程崩溃）。 */
    ERROR,

    /** 内核已关闭。 */
    CLOSED
}
