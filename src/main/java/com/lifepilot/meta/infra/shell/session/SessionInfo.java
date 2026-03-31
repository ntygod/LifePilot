package com.lifepilot.meta.infra.shell.session;

import java.time.Instant;

/**
 * 持久会话摘要信息 — 用于 shell.session.list 工具返回。
 *
 * @param sessionId      唯一会话标识
 * @param name           会话名称
 * @param state          当前状态
 * @param cwd            当前工作目录
 * @param createdAt      创建时间
 * @param lastAccessTime 最后访问时间
 * @author zsg
 * @since 2026-03-31
 */
public record SessionInfo(
        String sessionId,
        String name,
        SessionState state,
        String cwd,
        Instant createdAt,
        Instant lastAccessTime
) {}
