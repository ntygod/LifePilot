package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 创建会话请求。
 *
 * @param title     会话标题（可选）
 * @param projectId 归属项目 ID（可选，NULL = 归属主账户；非 NULL = 归属具体项目）
 * @author zsg
 * @since 2026-02-27
 */
public record CreateSessionRequest(
        String title,
        @Nullable String projectId
) {}
