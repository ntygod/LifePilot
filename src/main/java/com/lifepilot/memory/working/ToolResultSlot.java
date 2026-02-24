package com.lifepilot.memory.working;

import java.time.Instant;

/**
 * 工具结果槽位 — 缓存工具执行结果。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ToolResultSlot(
        String toolId,
        String toolAction,
        String result,
        int tokenCount,
        float importance,
        Instant createdAt
) implements WorkingMemorySlot {}
