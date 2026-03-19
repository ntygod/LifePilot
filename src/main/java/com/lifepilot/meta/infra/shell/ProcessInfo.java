package com.lifepilot.meta.infra.shell;

import java.time.Instant;

/**
 * 后台进程摘要信息 — 用于 process.list 工具返回。
 *
 * @param sessionId 会话标识
 * @param command 启动命令
 * @param state 当前状态
 * @param startTime 启动时间
 * @param workDir 工作目录
 * @author zsg
 * @since 2026-03-20
 */
public record ProcessInfo(
        String sessionId,
        String command,
        ProcessState state,
        Instant startTime,
        String workDir
) {}
