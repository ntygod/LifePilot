package com.lifepilot.tool.search;

import java.util.List;
import java.util.Map;

/**
 * 单条搜索命中。
 *
 * @param id 工具 ID
 * @param description 中文描述
 * @param category 工具 category 名称
 * @param score 融合分数（数值越大越相关）
 * @param actions action 名列表（若该工具支持 action 分发）
 * @param inputSchema 工具的 inputSchema（替代 tools.describe）
 * @author zsg
 * @since 2026-04-23
 */
public record ToolSearchHit(
        String id,
        String description,
        String category,
        double score,
        List<String> actions,
        Map<String, Object> inputSchema
) {
    public ToolSearchHit {
        actions = actions != null ? List.copyOf(actions) : List.of();
        inputSchema = inputSchema != null ? Map.copyOf(inputSchema) : Map.of();
    }
}
