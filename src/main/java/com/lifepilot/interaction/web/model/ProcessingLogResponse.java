package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 文档处理日志响应。
 *
 * @param logs 日志记录列表
 */
public record ProcessingLogResponse(
        List<LogEntry> logs
) {}
