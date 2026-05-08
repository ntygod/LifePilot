package com.lifepilot.memory.eval.loader;

import jakarta.annotation.Nullable;

import java.time.Instant;

/**
 * 一条对话消息。
 *
 * @param role      {@code user} / {@code assistant}
 * @param content   消息文本
 * @param timestamp 消息时间，可为 null
 * @author zsg
 * @since 2026-05-09
 */
public record BenchmarkMessage(
        String role,
        String content,
        @Nullable Instant timestamp
) {
    public BenchmarkMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException("role 必须是 user 或 assistant: " + role);
        }
        if (content == null) {
            content = "";
        }
    }
}
