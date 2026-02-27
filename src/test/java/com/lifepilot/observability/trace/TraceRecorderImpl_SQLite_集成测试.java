package com.lifepilot.observability.trace;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.redactor.DataRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceRecorderImpl → SQLite 写入读取 round-trip 集成测试。
 *
 * <p>使用内存 SQLite 验证 startTrace → recordStep → endTrace → TraceQuery.getDetail() 完整流程。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
class TraceRecorderImpl_SQLite_集成测试 {

    private JdbcTemplate jdbcTemplate;
    private TraceRecorderImpl recorder;
    private TraceQuery traceQuery;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建表结构
        jdbcTemplate.execute("""
                CREATE TABLE traces (
                    trace_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, goal TEXT NOT NULL,
                    start_time TEXT NOT NULL, end_time TEXT, total_duration_ms INTEGER,
                    total_steps INTEGER NOT NULL DEFAULT 0, total_tokens INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
                    success INTEGER NOT NULL DEFAULT 0, termination_reason TEXT, final_output TEXT,
                    error_message TEXT, metadata_json TEXT, created_at TEXT NOT NULL
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE trace_steps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, trace_id TEXT NOT NULL REFERENCES traces(trace_id),
                    step_index INTEGER NOT NULL, step_type TEXT NOT NULL, timestamp TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0, detail_json TEXT NOT NULL, created_at TEXT NOT NULL
                )""");
        // FTS5 虚拟表
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE traces_fts USING fts5(
                    trace_id, goal, final_output, content='traces', content_rowid='rowid'
                )""");
        jdbcTemplate.execute("""
                CREATE TRIGGER traces_ai AFTER INSERT ON traces BEGIN
                    INSERT INTO traces_fts(rowid, trace_id, goal, final_output)
                    VALUES (new.rowid, new.trace_id, new.goal, new.final_output);
                END""");

        var serializer = new TraceStepSerializer();
        var propagator = new TraceContextPropagator(false);
        var redactor = new DataRedactor();
        var properties = new ObservabilityProperties();

        recorder = new TraceRecorderImpl(jdbcTemplate, serializer, propagator, redactor, properties);
        traceQuery = new TraceQuery(jdbcTemplate, serializer, properties);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void startTrace_recordStep_endTrace_完整流程_可通过TraceQuery读取() {
        String traceId = UUID.randomUUID().toString();
        String sessionId = "session-001";
        String goal = "测试目标";

        // 开始追踪
        var ctx = recorder.startTrace(traceId, sessionId, goal);
        assertThat(ctx.traceId()).isEqualTo(traceId);

        // 记录多种步骤
        var llmStep = new LlmCallStep(0, Instant.now(), Duration.ofMillis(200),
                "openai", "gpt-4", "agent_reasoning", 100, 50, Duration.ofMillis(200), false, 0.7, "stop");
        recorder.recordStep(ctx, llmStep);

        var toolStep = new ToolCallStep(1, Instant.now(), Duration.ofMillis(100),
                "todo-add", "execute", "{}", "{\"ok\":true}", true, null, com.lifepilot.observability.guardrail.RiskLevel.LOW);
        recorder.recordStep(ctx, toolStep);

        var stateStep = new StateTransitionStep(2, Instant.now(), Duration.ofMillis(10),
                "PLANNING", "EXECUTING", "PlanGenerated", "计划生成: 2 步");
        recorder.recordStep(ctx, stateStep);

        // 结束追踪
        var record = recorder.endTrace(ctx, "任务完成", true, null, null);
        assertThat(record.traceId()).isEqualTo(traceId);
        assertThat(record.totalSteps()).isEqualTo(3);
        assertThat(record.success()).isTrue();

        // 等待异步写入完成
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // 通过 TraceQuery 读取验证
        var detail = traceQuery.getDetail(traceId);
        assertThat(detail.traceId()).isEqualTo(traceId);
        assertThat(detail.sessionId()).isEqualTo(sessionId);
        assertThat(detail.goal()).isEqualTo(goal);
        assertThat(detail.success()).isTrue();
        assertThat(detail.steps()).hasSize(3);

        // 验证步骤类型
        assertThat(detail.steps().get(0)).isInstanceOf(LlmCallStep.class);
        assertThat(detail.steps().get(1)).isInstanceOf(ToolCallStep.class);
        assertThat(detail.steps().get(2)).isInstanceOf(StateTransitionStep.class);

        // 验证 LlmCallStep 字段
        var readLlm = (LlmCallStep) detail.steps().get(0);
        assertThat(readLlm.providerId()).isEqualTo("openai");
        assertThat(readLlm.inputTokens()).isEqualTo(100);
        assertThat(readLlm.outputTokens()).isEqualTo(50);
    }

    @Test
    void recordStep_含敏感数据_脱敏后持久化() {
        String traceId = UUID.randomUUID().toString();
        var ctx = recorder.startTrace(traceId, "session-002", "脱敏测试");

        // 工具调用包含手机号
        var toolStep = new ToolCallStep(0, Instant.now(), Duration.ofMillis(50),
                "search", "query", "{\"phone\":\"13812345678\"}", "{\"result\":\"ok\"}", true, null, com.lifepilot.observability.guardrail.RiskLevel.LOW);
        recorder.recordStep(ctx, toolStep);

        recorder.endTrace(ctx, "完成", true, null, null);

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        var detail = traceQuery.getDetail(traceId);
        var readTool = (ToolCallStep) detail.steps().get(0);
        // 脱敏后手机号应被替换
        assertThat(readTool.inputJson()).doesNotContain("13812345678");
        assertThat(readTool.inputJson()).contains("138****5678");
    }
}
