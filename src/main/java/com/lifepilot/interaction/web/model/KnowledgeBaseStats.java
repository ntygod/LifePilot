package com.lifepilot.interaction.web.model;

/**
 * Analytics 知识库统计响应。
 *
 * @param kbId             知识库 ID
 * @param kbName           知识库名称
 * @param retrievalCount   检索次数
 * @param hitRate          命中率（0.0-1.0，可选）
 * @param avgRetrievalTime 平均检索时间（毫秒，可选）
 * @author zsg
 * @since 2026-02-28
 */
public record KnowledgeBaseStats(
        String kbId,
        String kbName,
        long retrievalCount,
        Double hitRate,
        Long avgRetrievalTime
) {
}
