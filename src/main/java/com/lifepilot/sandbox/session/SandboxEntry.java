package com.lifepilot.sandbox.session;

import java.nio.file.Path;
import java.time.Instant;

import com.lifepilot.sandbox.booter.SandboxBooter;

/**
 * 沙箱会话条目 — 存储单个会话的沙箱实例、最后访问时间和工作目录。
 *
 * @param booter         沙箱启动器实例
 * @param lastAccessTime 最后访问时间（用于 TTL 计算）
 * @param workingDirectory 工作目录路径
 * @author zsg
 * @since 2026-03-01
 */
record SandboxEntry(
    SandboxBooter booter,
    Instant lastAccessTime,
    Path workingDirectory
) {}
