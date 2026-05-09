package com.lifepilot.memory.retrieval.orchestrator;

/**
 * 检索意图 — 指导 {@link QueryPlanner} 选择合适的 {@link SourceAdapter} 集合。
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum RetrievalIntent {

    /** 事实查询 — HybridRetrieval + KnowledgeBase 优先。 */
    FACT,

    /** 经验查询 — ExperienceRetrieval + HybridRetrieval 优先。 */
    EXPERIENCE,

    /** 通用查询 — 三路全走。 */
    GENERAL
}
