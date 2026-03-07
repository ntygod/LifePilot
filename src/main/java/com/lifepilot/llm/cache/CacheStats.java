package com.lifepilot.llm.cache;

/**
 * 缓存统计快照。
 *
 * @author zsg
 * @since 2026-03-07
 */
public record CacheStats(
        long totalQueries,
        long hitCount,
        long missCount,
        int entryCount,
        long estimatedSavedTokens
) {
    /** 命中率。 */
    public double hitRate() {
        return totalQueries == 0 ? 0.0 : (double) hitCount / totalQueries;
    }
}
