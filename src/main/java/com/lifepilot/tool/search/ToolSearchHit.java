package com.lifepilot.tool.search;

import java.util.List;

/**
 * 单条搜索命中。
 *
 * @param id 工具 ID
 * @param description 英文短描述
 * @param category 工具 category 名称
 * @param score BM25 分数（数值越大越相关）
 * @param actions action 名列表（若该工具支持 action 分发）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolSearchHit(
        String id,
        String description,
        String category,
        double score,
        List<String> actions
) {
    public ToolSearchHit {
        actions = actions != null ? List.copyOf(actions) : List.of();
    }
}
