package com.lifepilot.interaction.web.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用量统计页面 Bug 条件探索测试。
 *
 * <p>验证两个 bug 的存在：
 * <ul>
 *   <li>Bug 1: 前端 TypeScript 接口使用 promptTokens/completionTokens，与后端 inputTokens/outputTokens 不匹配</li>
 *   <li>Bug 2: SQL strftime('%%Y-%%m-%%d', ...) 双百分号在 JdbcTemplate 中不被转义，导致日期显示为字面量 %Y-%m-%d</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 1.1, 1.2</b>
 *
 * @author zsg
 * @since 2026-03-06
 */
class UsageStatsDisplay_BugCondition_探索测试 {

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        // 创建内存 SQLite 数据库
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

            // 插入测试数据
            stmt.execute("""
                INSERT INTO traces (trace_id, session_id, user_message, total_tokens, input_tokens, output_tokens, start_time, end_time)
                VALUES ('t1', 's1', '你好', 500, 200, 300, '2026-02-28T10:00:00Z', '2026-02-28T10:00:05Z')
                """);
            stmt.execute("""
                INSERT INTO traces (trace_id, session_id, user_message, total_tokens, input_tokens, output_tokens, start_time, end_time)
                VALUES ('t2', 's1', '天气如何', 800, 350, 450, '2026-03-01T14:30:00Z', '2026-03-01T14:30:08Z')
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
     * Bug 1: 验证前端 TypeScript 接口 UsageStats 应使用 inputTokens/outputTokens 字段名。
     *
     * <p>当前前端使用 promptTokens/completionTokens（错误），与后端 inputTokens/outputTokens 不匹配。
     * 此测试读取 TypeScript 源文件，断言接口中使用的是 inputTokens/outputTokens（期望行为）。
     * 在未修复代码上，此断言将 FAIL，因为文件中使用的是 promptTokens/completionTokens。
     *
     * <p><b>Validates: Requirements 1.1</b>
     */
    @Test
    @DisplayName("Bug1_前端UsageStats接口应使用inputTokens而非promptTokens")
    void bug1_前端UsageStats接口应使用inputTokens而非promptTokens() throws IOException {
        // 读取前端 TypeScript 类型定义文件
        Path typesFile = Path.of("lifepilot-web/src/types/index.ts");
        assertTrue(Files.exists(typesFile), "前端类型定义文件应存在: " + typesFile);

        String content = Files.readString(typesFile);

        // 定位 UsageStats 接口定义区域（从 "interface UsageStats" 到下一个 "}" 结束）
        int usageStatsStart = content.indexOf("export interface UsageStats {");
        assertTrue(usageStatsStart >= 0, "应找到 UsageStats 接口定义");

        // 提取 UsageStats 接口及其嵌套的 dailyStats 类型（取足够长的范围）
        String usageStatsBlock = content.substring(usageStatsStart, Math.min(usageStatsStart + 800, content.length()));

        // 期望行为：接口中应使用 inputTokens/outputTokens（与后端对齐）
        // 在未修复代码上，接口使用 promptTokens/completionTokens，以下断言将 FAIL
        assertFalse(usageStatsBlock.contains("promptTokens"),
                "UsageStats 接口不应包含 promptTokens 字段（应为 inputTokens）");
        assertFalse(usageStatsBlock.contains("completionTokens"),
                "UsageStats 接口不应包含 completionTokens 字段（应为 outputTokens）");
    }

    /**
     * Bug 2: 验证 SQL strftime 双百分号转义导致日期格式错误。
     *
     * <p>执行与 AnalyticsController.getUsageStats 中完全相同的 SQL（使用 '%%Y-%%m-%%d'），
     * 断言返回的日期应匹配 YYYY-MM-DD 格式（期望行为）。
     * 在未修复代码上，SQLite 将 '%%Y' 解析为字面量 '%Y'，返回 '%Y-%m-%d'，断言将 FAIL。
     *
     * <p><b>Validates: Requirements 1.2</b>
     */
    @Test
    @DisplayName("Bug2_SQL_strftime双百分号应返回正确日期格式")
    void bug2_SQL_strftime双百分号应返回正确日期格式() throws SQLException {
        // 执行与 AnalyticsController 中完全相同的 SQL（包含双百分号 %%）
        // 这是未修复代码中的 SQL，JdbcTemplate 的 PreparedStatement 不会处理 % 转义
        String buggySql = """
                SELECT
                    strftime('%%Y-%%m-%%d', start_time) AS date,
                    COUNT(*) AS requests,
                    COALESCE(SUM(total_tokens), 0) AS tokens,
                    COALESCE(SUM(input_tokens), 0) AS input_tokens,
                    COALESCE(SUM(output_tokens), 0) AS output_tokens
                FROM traces
                WHERE start_time >= '2026-02-01T00:00:00Z' AND start_time <= '2026-03-31T23:59:59Z'
                GROUP BY strftime('%%Y-%%m-%%d', start_time)
                ORDER BY date
                """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(buggySql)) {

            assertTrue(rs.next(), "应至少返回一行每日统计数据");

            String dateResult = rs.getString("date");
            assertNotNull(dateResult, "日期字段不应为 null");

            // 期望行为：日期应匹配 YYYY-MM-DD 格式（如 2026-02-28）
            // 在未修复代码上，dateResult 将是 '%Y-%m-%d'（字面量），以下断言将 FAIL
            assertTrue(dateResult.matches("^\\d{4}-\\d{2}-\\d{2}$"),
                    "日期应匹配 YYYY-MM-DD 格式，但实际返回: '" + dateResult + "'（期望如 '2026-02-28'）");
        }
    }
}
