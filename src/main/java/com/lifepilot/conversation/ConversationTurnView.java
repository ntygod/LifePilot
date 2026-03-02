package com.lifepilot.conversation;

import java.time.Instant;

/**
 * 会话轮次视图模型（逻辑视图），统一抽象单条对话消息。
 *
 * <p>
 * 与底层存储的物理结构解耦，既可以由 {@code agent_sessions.recent_turns_json}
 * 反序列化生成，也可以由 L2 {@code EpisodicMemory} 的消息记录映射生成。
 * </p>
 */
public record ConversationTurnView(
        String sessionId,
        String role,
        String content,
        Instant createdAt,
        /**
         * 本轮相关的推理摘要（如有）。
         *
         * <p>
         * 对于来自 {@code SessionSnapshot.recentTurns} 的助手消息，
         * 该字段通常来自 {@code ConversationTurn.reasoningSummary()}。
         * 对于来自 L2 消息记录的视图，该字段通常为 {@code null}。
         * </p>
         */
        String reasoningSummary
) {
}

