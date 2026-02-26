package com.lifepilot.interaction.web.model;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param code      错误码
 * @param message   错误描述
 * @param timestamp 错误发生时间
 * @author zsg
 * @since 2026-02-27
 */
public record ErrorResponse(
        int code,
        String message,
        Instant timestamp
) {}
