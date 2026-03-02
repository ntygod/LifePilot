package com.lifepilot.conversation;

import java.time.Instant;

/**
 * 会话视图模型（逻辑视图），用于为记忆系统等下游提供统一的会话元信息抽象。
 *
 * <p>
 * 该视图从会话层（例如 {@code agent_sessions} 表）聚合而来，
 * 不关心底层物理表结构，也不承担写入职责。
 * </p>
 */
public record ConversationSessionView(
        String sessionId,
        String channelId,
        Instant lastActiveAt,
        int totalTurns,
        int totalTokensUsed
) {
}

