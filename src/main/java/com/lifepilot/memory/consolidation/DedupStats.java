package com.lifepilot.memory.consolidation;

/**
 * 去重统计结果。
 *
 * @param entitiesScanned 扫描的实体总数
 * @param candidatesFound 发现的重复候选对数
 * @param mergedCount     实际合并的对数
 * @param elapsedMs       执行耗时（毫秒）
 * @author zsg
 * @since 2026-03-15
 */
public record DedupStats(
        int entitiesScanned,
        int candidatesFound,
        int mergedCount,
        long elapsedMs
) {}
