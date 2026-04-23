package com.lifepilot.tool.tier1;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具使用统计持久化（SQLite）。
 *
 * <p>ON CONFLICT DO UPDATE 实现 upsert；会话覆盖率用当日 distinct session 数近似
 * （通过 session_count 累加，语义上 session_count 表示"本工具当日首次出现过的会话数"）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolUsageStatsRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolUsageStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一次调用；若 (toolId, date) 已存在则累加计数。
     *
     * @param toolId 工具 ID
     * @param statDate ISO 8601 日期
     * @param newSessionForTool 当前 session 今日首次用此工具，session_count +1；否则不加
     */
    public void recordInvocation(String toolId, String statDate, boolean newSessionForTool) {
        jdbcTemplate.update("""
                INSERT INTO tool_usage_stats (tool_id, stat_date, session_count, invocation_count)
                VALUES (?, ?, ?, 1)
                ON CONFLICT(tool_id, stat_date) DO UPDATE SET
                    session_count = session_count + excluded.session_count,
                    invocation_count = invocation_count + 1
                """,
                toolId, statDate, newSessionForTool ? 1 : 0);
    }

    /**
     * 计算近 windowDays 天各工具的会话覆盖率（session_count / 总 session_count）。
     *
     * <p>注意：分母取所有工具 session_count 之和，值域可能超过 1.0 如果同一会话用多个工具；
     * 当前实现为粗略近似，供 AdvisoryJob 排序候选使用。</p>
     *
     * @param windowDays 观察窗口天数
     * @return toolId → 覆盖率（0-1+）；空窗口返回空 Map
     */
    public Map<String, Double> computeSessionCoverage(int windowDays) {
        Integer totalSessions = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(session_count), 0) FROM tool_usage_stats
                WHERE stat_date >= date('now', ?)
                """, Integer.class, "-%d days".formatted(windowDays));
        if (totalSessions == null || totalSessions == 0) {
            return Map.of();
        }
        Map<String, Double> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tool_id, SUM(session_count) AS s
                FROM tool_usage_stats
                WHERE stat_date >= date('now', ?)
                GROUP BY tool_id
                """, "-%d days".formatted(windowDays));
        for (var row : rows) {
            result.put((String) row.get("tool_id"), ((Number) row.get("s")).intValue() / (double) totalSessions);
        }
        return result;
    }
}
