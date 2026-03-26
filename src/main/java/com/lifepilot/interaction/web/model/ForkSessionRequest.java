package com.lifepilot.interaction.web.model;

/**
 * 分叉会话请求。
 *
 * @param fromEntryId 起始 transcript 条目 ID（从此条目开始复制历史，包含此条目）
 * @param title         新会话标题（可选，默认使用原会话标题 + " (分叉)"）
 * @author zsg
 * @since 2026-02-28
 */
public record ForkSessionRequest(
        String fromEntryId,
        String title
) {}
