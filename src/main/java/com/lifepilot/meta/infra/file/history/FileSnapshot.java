package com.lifepilot.meta.infra.file.history;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 文件快照 — 记录某时刻的文件内容。
 *
 * @author zsg
 * @since 2026-03-31
 */
public record FileSnapshot(Path path, byte[] content, Instant timestamp) {}
