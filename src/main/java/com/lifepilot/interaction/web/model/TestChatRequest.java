package com.lifepilot.interaction.web.model;

/**
 * Agent 测试对话请求。
 *
 * @param message 测试消息内容
 * @author zsg
 * @since 2026-02-28
 */
public record TestChatRequest(
        String message
) {}
