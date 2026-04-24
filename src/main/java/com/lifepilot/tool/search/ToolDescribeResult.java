package com.lifepilot.tool.search;

import java.util.List;
import java.util.Map;

/**
 * `tools.describe` 返回结果。
 *
 * @param schemas 工具 ID → 完整 schema
 * @param notFound 请求但未找到的 ID 列表
 * @param suggestion 给 LLM 的提示
 * @author zsg
 * @since 2026-04-23
 */
public record ToolDescribeResult(
        Map<String, Object> schemas,
        List<String> notFound,
        String suggestion
) {
    public ToolDescribeResult {
        schemas = schemas != null ? Map.copyOf(schemas) : Map.of();
        notFound = notFound != null ? List.copyOf(notFound) : List.of();
    }
}
