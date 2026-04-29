package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.TraceContextPropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GuardrailEngine 审计日志持久化集成测试。
 *
 * <p>验证策略触发 Blocked/NeedsConfirmation 时，guardrail_logs 表有对应记录。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
class GuardrailEngine_审计日志_集成测试 {

    private JdbcTemplate jdbcTemplate;
    private GuardrailEngine engine;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("""
                CREATE TABLE guardrail_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT, tool_id TEXT,
                    policy_id TEXT NOT NULL, result_type TEXT NOT NULL, reason TEXT,
                    risk_level TEXT, approval_mode TEXT, user_decision TEXT, created_at TEXT NOT NULL
                )""");

        var propagator = new TraceContextPropagator(false);
        var properties = new ObservabilityProperties();
        engine = new GuardrailEngine(jdbcTemplate, propagator, properties);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void contentSafetyPolicy_阻断时_写入审计日志() {
        // 注册内容安全策略
        var policy = new ContentSafetyPolicy(
                "content-safety-1", true, 1,
                List.of("危险操作"), List.of());
        engine.registerPolicy(policy);

        // 检查包含阻断模式的内容
        var result = engine.checkInput(null, "请执行危险操作");

        assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);

        // 验证审计日志
        var logs = jdbcTemplate.queryForList("SELECT * FROM guardrail_logs");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("policy_id")).isEqualTo("content-safety-1");
        assertThat(logs.get(0).get("result_type")).isEqualTo("BLOCKED");
    }

    @Test
    void 多策略注册_Blocked结果_写入审计日志() {
        var policy1 = new ContentSafetyPolicy(
                "safety-1", true, 1, List.of("禁止词"), List.of());
        var policy2 = new ContentSafetyPolicy(
                "safety-2", true, 2, List.of("另一个禁止词"), List.of());
        engine.registerPolicy(policy1);
        engine.registerPolicy(policy2);

        // 触发第一个策略
        engine.checkInput(null, "包含禁止词的内容");

        var logs = jdbcTemplate.queryForList("SELECT * FROM guardrail_logs");
        assertThat(logs).isNotEmpty();
        // 短路逻辑：第一个策略阻断后不继续检查
        assertThat(logs.get(0).get("policy_id")).isEqualTo("safety-1");
    }
}
