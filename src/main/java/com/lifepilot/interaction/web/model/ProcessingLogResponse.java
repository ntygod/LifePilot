package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 文档处理日志响应。
 *
 * @param logs 日志记录列表
 * @author zsg
 * @since 2026-03-02
 */
public record ProcessingLogResponse(
        List<LogEntry> logs
) {}
