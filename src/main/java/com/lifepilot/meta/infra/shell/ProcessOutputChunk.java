package com.lifepilot.meta.infra.shell;

import jakarta.annotation.Nullable;

/**
 * 后台进程输出增量快照。
 *
 * @param stdout 自上次读取以来的 stdout 增量
 * @param stderr 自上次读取以来的 stderr 增量
 * @param output 为兼容旧协议保留的合并输出
 * @param state 当前进程状态
 * @param exitCode 真实退出码，运行中时为 null
 * @author zsg
 * @since 2026-03-30
 */
public record ProcessOutputChunk(
        String stdout,
        String stderr,
        String output,
        ProcessState state,
        @Nullable Integer exitCode
) {}
