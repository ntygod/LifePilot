package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GuardrailEngine 预算护栏集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class GuardrailEngine_预算护栏_集成测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TraceContextPropagator propagator;
    private GuardrailEngine engine;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE guardrail_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trace_id TEXT,
                    tool_id TEXT,
                    policy_id TEXT NOT NULL,
                    result_type TEXT NOT NULL,
                    reason TEXT,
                    risk_level TEXT,
                    approval_mode TEXT,
                    user_decision TEXT,
                    created_at TEXT NOT NULL
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE daily_token_usage (
                    usage_date TEXT PRIMARY KEY,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    total_tokens INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");

        propagator = new TraceContextPropagator(false);
        engine = new GuardrailEngine(jdbcTemplate, propagator, new ObservabilityProperties());
        engine.registerPolicy(GuardrailPolicy.budgetLimitPolicy("daily-budget", true, 20, 1000));
    }

    @AfterEach
    void tearDown() {
        propagator.unbind();
        dataSource.destroy();
    }

    @Test
    void 当天累计加当前Trace增量达到上限时_稳定阻断() {
        String usageDate = LocalDate.now().toString();
        jdbcTemplate.update("""
                INSERT INTO daily_token_usage (
                    usage_date, input_tokens, output_tokens, total_tokens, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                usageDate, 450, 450, 900, Instant.now().toString(), Instant.now().toString());

        TraceContext traceContext = new TraceContext("trace-1", "session-1", "测试预算护栏");
        traceContext.addStep(new LlmCallStep(
                0, Instant.now(), Duration.ofMillis(200),
                "openai", "gpt-5", "reasoning",
                60, 40, Duration.ofMillis(200),
                false, 0.7, "stop"
        ));
        propagator.bind(traceContext);

        BuiltinTool tool = BuiltinTool.builder()
                .id("test.echo")
                .name("test.echo")
                .description("测试工具")
                .inputSchema(JsonSchema.empty())
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build();

        GuardrailResult result = engine.checkToolCall(
                tool,
                new ToolInput(tool.id(), Map.of(), JsonSchema.empty(), null, null)
        );

        assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        GuardrailResult.Blocked blocked = (GuardrailResult.Blocked) result;
        assertThat(blocked.policyId()).isEqualTo("daily-budget");
        assertThat(blocked.reason()).contains("consumed=1000");

        Integer logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM guardrail_logs", Integer.class);
        assertThat(logCount).isEqualTo(1);
    }
}
