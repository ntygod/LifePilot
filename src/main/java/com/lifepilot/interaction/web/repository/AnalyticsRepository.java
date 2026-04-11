package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.ErrorTrendDaily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析统计数据仓库。
 *
 * <p>封装 {@code traces} 和 {@code trace_steps} 表的全部统计查询。</p>
 *
 * @author zsg
 * @since 2026-04-11
 */
@Repository
public class AnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── 内部 record 定义 ───

    /**
     * 每日 Token 统计行。
     */
    public record DailyTokenRow(String date, long requests, long tokens, long inputTokens, long outputTokens) {}

    /**
     * Agent 分组统计行。
     */
    public record AgentGroupRow(String metadataJson, long callCount, double avgResponseTime, long failureCount, long totalTokens) {}

    /**
     * 知识库分组统计行。
     */
    public record KbGroupRow(String detailJson, long retrievalCount, double avgRetrievalTime) {}

    /**
     * Tool 调用步骤行。
     */
    public record ToolCallStepRow(String detailJson, long durationMs, String timestamp) {}

    // ─── 查询方法 ───

    /**
     * 查询总体统计（总请求数、总 Token 数、输入 Token 数、输出 Token 数）。
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return 长度为 4 的数组：[totalRequests, totalTokens, inputTokens, outputTokens]
     */
    public long[] queryOverallStats(String from, String to) {
        var result = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total_requests,
                    COALESCE(SUM(total_tokens), 0) AS total_tokens,
                    COALESCE(SUM(input_tokens), 0) AS input_tokens,
                    COALESCE(SUM(output_tokens), 0) AS output_tokens
                FROM traces
                WHERE start_time >= ? AND start_time <= ?
                """, (rs, rowNum) -> new long[]{
                rs.getLong("total_requests"),
                rs.getLong("total_tokens"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens")
        }, from, to);
        return result != null ? result : new long[]{0, 0, 0, 0};
    }

    /**
     * 查询每日 Token 统计。
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return 每日统计行列表
     */
    public List<DailyTokenRow> queryDailyTokenStats(String from, String to) {
        return jdbcTemplate.query("""
                SELECT
                    strftime('%Y-%m-%d', start_time) AS date,
                    COUNT(*) AS requests,
                    COALESCE(SUM(total_tokens), 0) AS tokens,
                    COALESCE(SUM(input_tokens), 0) AS input_tokens,
                    COALESCE(SUM(output_tokens), 0) AS output_tokens
                FROM traces
                WHERE start_time >= ? AND start_time <= ?
                GROUP BY date
                ORDER BY date
                """, (rs, rowNum) -> new DailyTokenRow(
                rs.getString("date"),
                rs.getLong("requests"),
                rs.getLong("tokens"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens")
        ), from, to);
    }

    /**
     * 按 metadata_json 分组查询 Agent 统计。
     *
     * @param from 开始时间（可选，ISO 8601）
     * @param to   结束时间（可选，ISO 8601）
     * @return Agent 分组统计行列表
     */
    public List<AgentGroupRow> queryAgentGroupedStats(@Nullable String from, @Nullable String to) {
        var sql = new StringBuilder("""
                SELECT
                    t.metadata_json,
                    COUNT(*) AS call_count,
                    COALESCE(AVG(t.total_duration_ms), 0) AS avg_response_time,
                    COALESCE(SUM(CASE WHEN t.success = 0 THEN 1 ELSE 0 END), 0) AS failure_count,
                    COALESCE(SUM(t.total_tokens), 0) AS total_tokens
                FROM traces t
                WHERE 1=1
                """);

        List<Object> params = new ArrayList<>();

        if (from != null && !from.isBlank()) {
            sql.append(" AND t.start_time >= ?");
            params.add(Instant.parse(from).toString());
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND t.start_time <= ?");
            params.add(Instant.parse(to).toString());
        }

        sql.append(" GROUP BY t.metadata_json");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new AgentGroupRow(
                rs.getString("metadata_json"),
                rs.getLong("call_count"),
                rs.getDouble("avg_response_time"),
                rs.getLong("failure_count"),
                rs.getLong("total_tokens")
        ), params.toArray());
    }

    /**
     * 按 detail_json 分组查询知识库检索统计。
     *
     * @param from 开始时间（可选，ISO 8601）
     * @param to   结束时间（可选，ISO 8601）
     * @return 知识库分组统计行列表
     */
    public List<KbGroupRow> queryKbGroupedStats(@Nullable String from, @Nullable String to) {
        var sql = new StringBuilder("""
                SELECT
                    ts.detail_json,
                    COUNT(*) AS retrieval_count,
                    COALESCE(AVG(ts.duration_ms), 0) AS avg_retrieval_time
                FROM trace_steps ts
                JOIN traces t ON ts.trace_id = t.trace_id
                WHERE ts.step_type = 'tool_call'
                  AND (ts.detail_json LIKE '%%"toolId":"knowledge_base.%%'
                   OR ts.detail_json LIKE '%%"toolId":"kb.%%')
                """);

        List<Object> params = new ArrayList<>();

        if (from != null && !from.isBlank()) {
            sql.append(" AND t.start_time >= ?");
            params.add(Instant.parse(from).toString());
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND t.start_time <= ?");
            params.add(Instant.parse(to).toString());
        }

        sql.append(" GROUP BY ts.detail_json");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new KbGroupRow(
                rs.getString("detail_json"),
                rs.getLong("retrieval_count"),
                rs.getDouble("avg_retrieval_time")
        ), params.toArray());
    }

    /**
     * 查询 Tool 调用步骤原始数据。
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return Tool 调用步骤行列表
     */
    public List<ToolCallStepRow> queryToolCallSteps(String from, String to) {
        return jdbcTemplate.query("""
                SELECT ts.detail_json, ts.duration_ms, ts.timestamp
                FROM trace_steps ts
                JOIN traces t ON ts.trace_id = t.trace_id
                WHERE ts.step_type = 'tool_call'
                  AND t.start_time >= ?
                  AND t.start_time <= ?
                """, (rs, rowNum) -> new ToolCallStepRow(
                rs.getString("detail_json"),
                rs.getLong("duration_ms"),
                rs.getString("timestamp")
        ), from, to);
    }

    /**
     * 查询每日错误趋势。
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return 每日错误趋势列表
     */
    public List<ErrorTrendDaily> queryErrorTrend(String from, String to) {
        return jdbcTemplate.query("""
                SELECT
                    strftime('%Y-%m-%d', t.start_time) AS date,
                    COUNT(*) AS total_errors,
                    COALESCE(SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM trace_steps ts
                        WHERE ts.trace_id = t.trace_id
                          AND ts.step_type = 'tool_call'
                          AND ts.detail_json LIKE '%%"success":false%%'
                    ) THEN 1 ELSE 0 END), 0) AS tool_errors
                FROM traces t
                WHERE t.success = 0
                  AND t.start_time >= ?
                  AND t.start_time <= ?
                GROUP BY strftime('%Y-%m-%d', t.start_time)
                ORDER BY date
                """, (rs, rowNum) -> {
            String date = rs.getString("date");
            long totalErrors = rs.getLong("total_errors");
            long toolErrors = rs.getLong("tool_errors");
            long agentErrors = totalErrors - toolErrors;
            return new ErrorTrendDaily(date, agentErrors, toolErrors, totalErrors);
        }, from, to);
    }
}
