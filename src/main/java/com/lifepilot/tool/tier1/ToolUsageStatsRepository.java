package com.lifepilot.tool.tier1;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具使用统计持久化（SQLite）。
 *
 * <p>{@code tool_usage_stats} 记录每工具每日的 distinct session 数（session_count）和
 * 调用次数（invocation_count）；{@code daily_active_sessions} 独立记录每日全局活跃
 * session ID 集合。Tier 1 覆盖率 = 该工具当日 session_count / 当日活跃 session 总数，
 * 真实反映"用此工具的会话占比"。</p>
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
     * 记录某 session 当日首次活跃（幂等）。
     *
     * <p>(stat_date, session_id) 复合主键；重复调用 {@code INSERT OR IGNORE} 无副作用。
     * 供 AdvisoryJob 计算覆盖率分母使用。</p>
     */
    public void recordActiveSession(String sessionId, String statDate) {
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO daily_active_sessions (stat_date, session_id)
                VALUES (?, ?)
                """, statDate, sessionId);
    }

    /**
     * 计算近 windowDays 天各工具的会话覆盖率。
     *
     * <p>分母 = 当日任一工具出现过的 distinct session 总数（来自 daily_active_sessions）；
     * 分子 = 该工具 session_count 之和。值域 [0, 1]，真实反映"有多少会话用过此工具"。</p>
     *
     * @param windowDays 观察窗口天数
     * @return toolId → 覆盖率；空窗口或无数据时返回空 Map
     */
    public Map<String, Double> computeSessionCoverage(int windowDays) {
        String sinceClause = "-%d days".formatted(windowDays);
        Integer totalSessions = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM daily_active_sessions
                WHERE stat_date >= date('now', ?)
                """, Integer.class, sinceClause);
        if (totalSessions == null || totalSessions == 0) {
            return Map.of();
        }
        Map<String, Double> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT tool_id, SUM(session_count) AS s
                FROM tool_usage_stats
                WHERE stat_date >= date('now', ?)
                GROUP BY tool_id
                """, sinceClause);
        for (var row : rows) {
            result.put((String) row.get("tool_id"), ((Number) row.get("s")).intValue() / (double) totalSessions);
        }
        return result;
    }
}
