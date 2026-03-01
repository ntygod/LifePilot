package com.lifepilot.memory.consolidation;

/**
 * 巩固统计结果 — 记录每次巩固管线执行的统计数据。
 *
 * <p>包含分析对话数、发现实体数、提升实体数、触发提取数、
 * 创建模板数、更新模板数和耗时，用于巩固日志记录和监控。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record ConsolidationStats(
        String consolidationType,
        int conversationsAnalyzed,
        int entitiesFound,
        int entitiesBoosted,
        int extractionsTriggered,
        int templatesCreated,
        int templatesUpdated,
        long elapsedMs
) {}
