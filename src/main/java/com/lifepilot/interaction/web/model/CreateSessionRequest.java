package com.lifepilot.interaction.web.model;

/**
 * 创建会话请求。
 *
 * @param title 会话标题（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record CreateSessionRequest(
        String title
) {}
