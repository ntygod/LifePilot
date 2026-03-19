package com.lifepilot.meta.infra.shell;

/**
 * 后台进程状态枚举。
 *
 * @author zsg
 * @since 2026-03-20
 */
public enum ProcessState {

    /** 进程正在运行。 */
    RUNNING,

    /** 进程正常退出（exitCode == 0）。 */
    COMPLETED,

    /** 进程异常退出（exitCode != 0）。 */
    FAILED,

    /** 进程被用户主动终止。 */
    KILLED
}
