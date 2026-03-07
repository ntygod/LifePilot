package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 上下文组装预览请求。
 *
 * @param message   测试消息内容
 * @param sessionId 可选的会话 ID
 * @author zsg
 * @since 2026-03-07
 */
public record ContextPreviewRequest(
        String message,
        @Nullable String sessionId
) {}
