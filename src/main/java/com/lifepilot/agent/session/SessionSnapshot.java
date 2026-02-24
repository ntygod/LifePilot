package com.lifepilot.agent.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 会话快照 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record SessionSnapshot(
        String sessionId,
        String channelId,
        List<ConversationTurn> recentTurns,
        List<String> mentionedEntities,
        Instant lastActiveAt,
        int totalTurns,
        int totalTokensUsed
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public SessionSnapshot {
        recentTurns = List.copyOf(recentTurns);
        mentionedEntities = List.copyOf(mentionedEntities);
    }

    /**
     * 当距 lastActiveAt 超过 timeout 时返回 true。
     *
     * @param timeout 超时时长
     * @return 是否已过期
     */
    public boolean isExpired(Duration timeout) {
        return Duration.between(lastActiveAt, Instant.now()).compareTo(timeout) > 0;
    }
}
