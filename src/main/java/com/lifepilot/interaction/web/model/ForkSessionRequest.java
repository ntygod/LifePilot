package com.lifepilot.interaction.web.model;

/**
 * 分叉会话请求。
 *
 * @param fromMessageId 起始消息 ID（从此消息开始复制历史，包含此消息）
 * @param title         新会话标题（可选，默认使用原会话标题 + " (分叉)"）
 * @author zsg
 * @since 2026-02-28
 */
public record ForkSessionRequest(
        String fromMessageId,
        String title
) {}
