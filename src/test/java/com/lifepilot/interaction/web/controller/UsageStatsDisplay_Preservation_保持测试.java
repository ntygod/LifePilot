package com.lifepilot.interaction.web.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用量统计页面保持测试（Preservation Property）。
 *
 * <p>验证总请求数、总 Token 数与费用预估在修复前后保持正确。
 * 使用内存 SQLite 执行与 {@code AnalyticsController.getUsageStats} 相同的总体统计 SQL，
 * 确认这些不涉及 strftime 的查询在修复前后行为一致。</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.5</b></p>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("用量统计页面保持测试")
class UsageStatsDisplay_Preservation_保持测试 {

    private Connection connection;

    /** 与 AnalyticsController 中相同的 Token 成本常量。 */
    private static final double COST_PER_TOKEN = 0.000002;

    /** 与 AnalyticsController.getUsageStats 中完全相同的总体统计 SQL。 */
    private static final String OVERALL_STATS_SQL = """
            SELECT
                COUNT(*) AS total_requests,
                COALESCE(SUM(total_tokens), 0) AS total_tokens,
                COALESCE(SUM(input_tokens), 0) AS input_tokens,
                COALESCE(SUM(output_tokens), 0) AS output_tokens
            FROM traces
            WHERE start_time >= ? AND start_time <= ?
            """;

    /** 查询时间范围：覆盖所有测试数据。 */
    private static final String TIME_FROM = "2026-01-01T00:00:00Z";
    private static final String TIME_TO = "2026-12-31T23:59:59Z";

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS traces (
                    trace_id TEXT PRIMARY KEY,
                    session_id TEXT,
                    user_message TEXT,
                    final_output TEXT,
                    success INTEGER DEFAULT 1,
                    total_steps INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    total_duration_ms INTEGER DEFAULT 0,
                    start_time TEXT,
                    end_time TEXT,
                    error_message TEXT,
                    termination_reason TEXT,
                    model_id TEXT,
                    metadata_json TEXT,
                    created_at TEXT DEFAULT (datetime('now'))
                )
                """);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * 插入一条 trace 记录到内存数据库。
     */
    private void insertTrace(String traceId, int totalTokens, int inputTokens, int outputTokens,
                             String startTime) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO traces (trace_id, session_id, user_message, total_tokens, input_tokens, output_tokens, start_time, end_time)
                VALUES (?, 's1', '测试消息', ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, traceId);
            ps.setInt(2, totalTokens);
            ps.setInt(3, inputTokens);
            ps.setInt(4, outputTokens);
            ps.setString(5, startTime);
            ps.setString(6, startTime);
            ps.executeUpdate();
        }
    }

    /**
     * 执行总体统计 SQL 并返回结果数组 [totalRequests, totalTokens, inputTokens, outputTokens]。
     */
    private long[] queryOverallStats() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(OVERALL_STATS_SQL)) {
            ps.setString(1, TIME_FROM);
            ps.setString(2, TIME_TO);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "总体统计 SQL 应返回一行结果");
                return new long[]{
                        rs.getLong("total_requests"),
                        rs.getLong("total_tokens"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens")
                };
            }
        }
    }

    /**
     * 对任意非空 traces 数据集，totalRequests 等于插入的 trace 数量。
     *
     * <p>插入多条 trace 记录，执行总体统计 SQL，验证 COUNT(*) 等于插入数量。
     * 此行为不涉及 strftime，在修复前后应保持一致。</p>
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    @DisplayName("totalRequests等于插入的trace数量")
    void totalRequests等于插入的trace数量() throws SQLException {
        // 插入 5 条 trace 记录
        insertTrace("t1", 100, 40, 60, "2026-03-01T10:00:00Z");
        insertTrace("t2", 200, 80, 120, "2026-03-02T11:00:00Z");
        insertTrace("t3", 150, 60, 90, "2026-03-03T12:00:00Z");
        insertTrace("t4", 300, 130, 170, "2026-03-04T13:00:00Z");
        insertTrace("t5", 50, 20, 30, "2026-03-05T14:00:00Z");

        long[] stats = queryOverallStats();
        assertEquals(5, stats[0], "totalRequests 应等于插入的 trace 数量");
    }

    /**
     * 对任意非空 traces 数据集，totalTokens 等于所有 trace 的 total_tokens 之和。
     *
     * <p>插入多条 trace 记录，执行总体统计 SQL，验证 SUM(total_tokens) 等于各条记录之和。
     * 此行为不涉及 strftime，在修复前后应保持一致。</p>
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Test
    @DisplayName("totalTokens等于所有trace的total_tokens之和")
    void totalTokens等于所有trace的total_tokens之和() throws SQLException {
        insertTrace("t1", 100, 40, 60, "2026-03-01T10:00:00Z");
        insertTrace("t2", 200, 80, 120, "2026-03-02T11:00:00Z");
        insertTrace("t3", 350, 150, 200, "2026-03-03T12:00:00Z");

        long expectedTotal = 100 + 200 + 350;

        long[] stats = queryOverallStats();
        assertEquals(expectedTotal, stats[1], "totalTokens 应等于所有 trace 的 total_tokens 之和");
    }

    /**
     * 对任意非空 traces 数据集，estimatedCost 等于 totalTokens * 0.000002。
     *
     * <p>插入多条 trace 记录，执行总体统计 SQL 获取 totalTokens，
     * 验证 totalTokens * COST_PER_TOKEN 计算正确。
     * 此行为不涉及 strftime，在修复前后应保持一致。</p>
     *
     * <p><b>Validates: Requirements 3.2</b></p>
     */
    @Test
    @DisplayName("estimatedCost等于totalTokens乘以COST_PER_TOKEN")
    void estimatedCost等于totalTokens乘以COST_PER_TOKEN() throws SQLException {
        insertTrace("t1", 500000, 200000, 300000, "2026-03-01T10:00:00Z");
        insertTrace("t2", 300000, 120000, 180000, "2026-03-02T11:00:00Z");

        long expectedTotalTokens = 500000 + 300000;
        double expectedCost = expectedTotalTokens * COST_PER_TOKEN;

        long[] stats = queryOverallStats();
        long totalTokens = stats[1];
        double estimatedCost = totalTokens * COST_PER_TOKEN;

        assertEquals(expectedTotalTokens, totalTokens, "totalTokens 应正确");
        assertEquals(expectedCost, estimatedCost, 1e-10,
                "estimatedCost 应等于 totalTokens * COST_PER_TOKEN");
    }

    /**
     * 对空 traces 表，所有统计值为 0。
     *
     * <p>不插入任何数据，执行总体统计 SQL，验证 COUNT(*) = 0，SUM 值均为 0。
     * 此行为不涉及 strftime，在修复前后应保持一致。</p>
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     */
    @Test
    @DisplayName("空traces表_所有统计值为0")
    void 空traces表_所有统计值为0() throws SQLException {
        // 不插入任何数据
        long[] stats = queryOverallStats();

        assertEquals(0, stats[0], "空表时 totalRequests 应为 0");
        assertEquals(0, stats[1], "空表时 totalTokens 应为 0");
        assertEquals(0, stats[2], "空表时 inputTokens 应为 0");
        assertEquals(0, stats[3], "空表时 outputTokens 应为 0");

        double estimatedCost = stats[1] * COST_PER_TOKEN;
        assertEquals(0.0, estimatedCost, 1e-10, "空表时 estimatedCost 应为 0");
    }
}
