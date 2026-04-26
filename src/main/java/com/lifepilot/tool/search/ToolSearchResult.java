package com.lifepilot.tool.search;

import java.util.List;

/**
 * `tools.search` 返回结果。
 *
 * @param results top-k 命中
 * @param totalMatched FTS5 实际命中总数
 * @param confidence 置信度
 * @param hint 给 LLM 的提示（零结果或低置信时非空）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolSearchResult(
        List<ToolSearchHit> results,
        int totalMatched,
        ToolSearchConfidence confidence,
        String hint
) {
    public ToolSearchResult {
        results = results != null ? List.copyOf(results) : List.of();
    }

    /** 零结果工厂方法。 */
    public static ToolSearchResult empty() {
        return new ToolSearchResult(
                List.of(),
                0,
                ToolSearchConfidence.NONE,
                "未匹配到工具。放宽关键词重新调 tools.search，或在 skill_catalog 评估对应 skill / 用 shell.exec 兜底。"
        );
    }
}
