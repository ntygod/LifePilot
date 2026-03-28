package com.lifepilot.interaction.web.model;

import jakarta.annotation.Nullable;

import java.time.Instant;

/**
 * 会话上下文压缩状态。
 *
 * @author zsg
 * @since 2026-03-28
 */
public record SessionCompactionStatusInfo(
        boolean enabled,
        int activeTranscriptTokens,
        int triggerThresholdTokens,
        int triggerThresholdPercent,
        int remainingTokens,
        int activeTurnCount,
        int minTurnCount,
        int keepRecentTurns,
        boolean thresholdReached,
        boolean minTurnsReached,
        boolean readyToCompact,
        int compactionCount,
        @Nullable Instant lastCompactedAt
) {
}
