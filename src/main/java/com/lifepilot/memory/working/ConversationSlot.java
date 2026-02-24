package com.lifepilot.memory.working;

import org.springframework.lang.Nullable;
import java.time.Instant;

/**
 * 对话槽位 — 存储用户消息或 Agent 回复。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ConversationSlot(
        String role,
        String content,
        int tokenCount,
        float importance,
        boolean isPinned,
        @Nullable String toolCallJson,
        Instant createdAt
) implements WorkingMemorySlot {

    /** 创建用户消息槽位（importance=0.8）。 */
    public static ConversationSlot userMessage(String content, int tokenCount) {
        return new ConversationSlot("user", content, tokenCount, 0.8f, false, null, Instant.now());
    }

    /** 创建 Agent 回复槽位（importance=0.6）。 */
    public static ConversationSlot assistantMessage(String content, int tokenCount) {
        return new ConversationSlot("assistant", content, tokenCount, 0.6f, false, null, Instant.now());
    }

    /** 创建系统消息槽位（importance=0.9, pinned=true）。 */
    public static ConversationSlot systemMessage(String content, int tokenCount) {
        return new ConversationSlot("system", content, tokenCount, 0.9f, true, null, Instant.now());
    }
}
