package com.lifepilot.memory.eval.loader;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 评估用例内的一段对话 session。
 *
 * @param sessionId 数据集内稳定的 session 标识
 * @param timestamp session 发生时间（可为 null，某些数据集无标注）
 * @param messages  按顺序的对话消息
 * @author zsg
 * @since 2026-05-09
 */
public record BenchmarkSession(
        String sessionId,
        @Nullable Instant timestamp,
        List<BenchmarkMessage> messages
) {
    public BenchmarkSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
