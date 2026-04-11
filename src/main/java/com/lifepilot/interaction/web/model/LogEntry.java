package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.Map;

/**
 * 单个日志条目。
 *
 * @param timestamp 时间戳
 * @param level     日志级别（INFO/WARN/ERROR）
 * @param message   日志消息
 * @param details   详细信息（可选）
 * @author zsg
 * @since 2026-03-02
 */
public record LogEntry(
        Instant timestamp,
        String level,
        String message,
        Map<String, Object> details
) {}
