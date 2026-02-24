package com.lifepilot.memory.episodic;

import org.springframework.lang.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * 对话记录 — L2 情景记忆的对话级不可变存储单元。
 *
 * <p>包含一次完整对话的所有消息，消息列表在构造时通过 List.copyOf() 确保不可变。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ConversationRecord(
        String id,
        String sessionId,
        String goal,
        @Nullable String summary,
        List<MessageRecord> messages,
        Instant createdAt,
        Instant updatedAt
) {

    /** 确保消息列表不可变。 */
    public ConversationRecord {
        messages = List.copyOf(messages);
    }

    /** 计算对话的总有效 Token 数。 */
    public int totalTokenCount() {
        return messages.stream()
                .mapToInt(MessageRecord::effectiveTokenCount)
                .sum();
    }

    /** 返回消息数量。 */
    public int messageCount() {
        return messages.size();
    }
}
