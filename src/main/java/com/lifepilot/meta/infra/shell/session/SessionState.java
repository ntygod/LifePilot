package com.lifepilot.meta.infra.shell.session;

/**
 * tmux 持久会话状态枚举。
 *
 * @author zsg
 * @since 2026-03-31
 */
public enum SessionState {

    /** 会话空闲，等待命令。 */
    IDLE,

    /** 会话正在执行命令。 */
    ACTIVE,

    /** 会话已关闭。 */
    CLOSED
}
