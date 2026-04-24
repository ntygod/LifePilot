package com.lifepilot.tool.search;

import java.util.List;
import java.util.Map;

/**
 * `tools.list` 返回结果。
 *
 * @param categories category 名 → 该 category 下的工具 ID 列表
 * @param total 所有返回 ID 的总数
 * @author zsg
 * @since 2026-04-23
 */
public record ToolListResult(
        Map<String, List<String>> categories,
        int total
) {
    public ToolListResult {
        categories = categories != null ? Map.copyOf(categories) : Map.of();
    }
}
