package com.lifepilot.tool.tier1;

/**
 * 每日工具使用统计。
 *
 * @param toolId 工具 ID
 * @param statDate ISO 8601 日期（yyyy-MM-dd）
 * @param sessionCount 当日涉及此工具的会话数
 * @param invocationCount 当日调用次数
 * @author zsg
 * @since 2026-04-23
 */
public record ToolUsageStats(
        String toolId,
        String statDate,
        int sessionCount,
        int invocationCount
) {}
