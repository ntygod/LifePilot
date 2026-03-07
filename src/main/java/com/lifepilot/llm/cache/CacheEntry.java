package com.lifepilot.llm.cache;

import java.time.Instant;

/**
 * 语义缓存条目。
 *
 * @author zsg
 * @since 2026-03-07
 */
public record CacheEntry(
        String id,
        String responseText,
        String scene,
        String agentPhase,
        String modelName,
        float similarityScore,
        int hitCount,
        Instant createdAt,
        Instant lastAccessedAt
) {}
