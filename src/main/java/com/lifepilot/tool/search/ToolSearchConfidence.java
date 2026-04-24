package com.lifepilot.tool.search;

/**
 * 搜索结果置信度。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ToolSearchConfidence {
    /** top-1 BM25 分数 ≥ 阈值。 */
    HIGH,
    /** top-1 分数 < 阈值但仍有结果。 */
    LOW,
    /** 零结果。 */
    NONE
}
