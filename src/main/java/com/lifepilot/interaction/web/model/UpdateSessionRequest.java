package com.lifepilot.interaction.web.model;

/**
 * 更新会话请求。
 *
 * @param title    新标题（可选）
 * @param pinned   是否置顶（可选）
 * @param archived 是否归档（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record UpdateSessionRequest(
        String title,
        Boolean pinned,
        Boolean archived
) {}
