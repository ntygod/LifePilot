package com.lifepilot.observability.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Flyway V19 迁移脚本集成测试。
 *
 * <p>手动执行 V19 DDL，验证表、索引、FTS5 虚拟表和触发器正确创建。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
class FlywayV19_迁移脚本_集成测试 {

    private JdbcTemplate jdbcTemplate;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 执行 V19 迁移脚本中的所有 DDL
        executeMigration();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void traces表_创建成功_可插入查询() {
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO traces (trace_id, session_id, goal, start_time, total_steps, success, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                traceId, "s1", "测试目标", Instant.now().toString(), 3, 1, Instant.now().toString());

        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM traces", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void trace_steps表_外键关联traces() {
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("PRAGMA foreign_keys = ON");
        jdbcTemplate.update("""
                INSERT INTO traces (trace_id, session_id, goal, start_time, success, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                traceId, "s1", "目标", Instant.now().toString(), 1, Instant.now().toString());

        assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO trace_steps (trace_id, step_index, step_type, timestamp, duration_ms, detail_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                traceId, 0, "llm_call", Instant.now().toString(), 100, "{}", Instant.now().toString())
        ).doesNotThrowAnyException();
    }

    @Test
    void guardrail_logs表_创建成功() {
        assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO guardrail_logs (policy_id, result_type, reason, created_at)
                VALUES (?, ?, ?, ?)""",
                "policy-1", "Blocked", "测试原因", Instant.now().toString())
        ).doesNotThrowAnyException();

        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM guardrail_logs", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void evaluation_results表_创建成功() {
        assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO evaluation_results (trace_id, evaluated_at,
                    tool_selection_score, parameter_validity_score, step_efficiency_score,
                    policy_compliance_score, token_efficiency_score, overall_score,
                    actual_steps, actual_tokens, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID().toString(), Instant.now().toString(),
                0.9, 0.8, 0.7, 1.0, 0.6, 0.82, 5, 1500, Instant.now().toString())
        ).doesNotThrowAnyException();
    }

    @Test
    void traces_fts_全文搜索_触发器正常工作() {
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO traces (trace_id, session_id, goal, start_time, success, final_output, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                traceId, "s1", "search weather forecast", Instant.now().toString(), 1, "sunny today", Instant.now().toString());

        // FTS5 搜索（使用英文避免 unicode61 分词器对中文支持不佳的问题）
        var results = jdbcTemplate.queryForList(
                "SELECT trace_id FROM traces_fts WHERE traces_fts MATCH ?", "weather");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("trace_id")).isEqualTo(traceId);
    }

    /** 执行 V19 迁移脚本中的核心 DDL。 */
    private void executeMigration() {
        jdbcTemplate.execute("""
                CREATE TABLE traces (
                    trace_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, goal TEXT NOT NULL,
                    start_time TEXT NOT NULL, end_time TEXT, total_duration_ms INTEGER,
                    total_steps INTEGER NOT NULL DEFAULT 0, total_tokens INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
                    success INTEGER NOT NULL DEFAULT 0, termination_reason TEXT, final_output TEXT,
                    error_message TEXT, metadata_json TEXT, created_at TEXT NOT NULL
                )""");
        jdbcTemplate.execute("CREATE INDEX idx_traces_session_id ON traces(session_id)");
        jdbcTemplate.execute("CREATE INDEX idx_traces_start_time ON traces(start_time)");
        jdbcTemplate.execute("CREATE INDEX idx_traces_success ON traces(success)");

        jdbcTemplate.execute("""
                CREATE TABLE trace_steps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT NOT NULL REFERENCES traces(trace_id),
                    step_index INTEGER NOT NULL, step_type TEXT NOT NULL, timestamp TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0, detail_json TEXT NOT NULL, created_at TEXT NOT NULL
                )""");
        jdbcTemplate.execute("CREATE INDEX idx_trace_steps_trace_id ON trace_steps(trace_id)");

        jdbcTemplate.execute("""
                CREATE TABLE guardrail_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT, tool_id TEXT,
                    policy_id TEXT NOT NULL, result_type TEXT NOT NULL, reason TEXT,
                    risk_level TEXT, approval_mode TEXT, user_decision TEXT, created_at TEXT NOT NULL
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE redaction_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT, context TEXT,
                    applied_rules_json TEXT, created_at TEXT NOT NULL
                )""");

        jdbcTemplate.execute("""
                CREATE TABLE evaluation_results (
                    trace_id TEXT PRIMARY KEY, evaluated_at TEXT NOT NULL,
                    tool_selection_score REAL NOT NULL, parameter_validity_score REAL NOT NULL,
                    step_efficiency_score REAL NOT NULL, policy_compliance_score REAL NOT NULL,
                    token_efficiency_score REAL NOT NULL, overall_score REAL NOT NULL,
                    actual_steps INTEGER NOT NULL, actual_tokens INTEGER NOT NULL,
                    violations_json TEXT, suggestions_json TEXT, created_at TEXT NOT NULL
                )""");

        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE traces_fts USING fts5(
                    trace_id, goal, final_output, content='traces', content_rowid='rowid'
                )""");
        jdbcTemplate.execute("""
                CREATE TRIGGER traces_ai AFTER INSERT ON traces BEGIN
                    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
                    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
                END""");
        jdbcTemplate.execute("""
                CREATE TRIGGER traces_ad AFTER DELETE ON traces BEGIN
                    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
                    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
                END""");
        jdbcTemplate.execute("""
                CREATE TRIGGER traces_au AFTER UPDATE ON traces BEGIN
                    INSERT INTO traces_fts(traces_fts, rowid, trace_id, goal, final_output)
                    VALUES ('delete', old.rowid, old.trace_id, old.goal, old.final_output);
                    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
                    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
                END""");
    }
}
