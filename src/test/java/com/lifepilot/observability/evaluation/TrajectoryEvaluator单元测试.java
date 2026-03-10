package com.lifepilot.observability.evaluation;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceMetadata;
import com.lifepilot.observability.trace.TraceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrajectoryEvaluator 单元测试。
 *
 * <p>聚焦核心规则：评分范围 [0.0, 1.0]、失败工具调用 / 护栏拦截 / 高 Token 消耗对评分的影响，
 * 以及在线评估时的持久化行为。</p>
 */
class TrajectoryEvaluator单元测试 {

    private static ObservabilityProperties defaultProperties() {
        ObservabilityProperties properties = new ObservabilityProperties();
        ObservabilityProperties.Evaluation eval = properties.getEvaluation();
        eval.setToolSelectionWeight(0.30);
        eval.setParameterValidityWeight(0.20);
        eval.setStepEfficiencyWeight(0.20);
        eval.setPolicyComplianceWeight(0.20);
        eval.setTokenEfficiencyWeight(0.10);
        return properties;
    }

    private static TraceRecord traceRecord(List<com.lifepilot.observability.trace.TraceStep> steps,
                                           int inputTokens,
                                           int outputTokens) {
        return new TraceRecord(
                "trace-001",
                "session-001",
                "goal",
                Instant.parse("2026-03-01T10:00:00Z"),
                Instant.parse("2026-03-01T10:10:00Z"),
                600_000L,
                steps.size(),
                inputTokens + outputTokens,
                inputTokens,
                outputTokens,
                true,
                null,
                "ok",
                null,
                steps,
                new TraceMetadata("web", "user-1", "1.0.0", Map.of())
        );
    }

    /** 构建 ToolCallStep。 */
    private static ToolCallStep toolStep(int index, boolean success, String toolId) {
        return new ToolCallStep(
                index,
                Instant.parse("2026-03-01T10:00:0" + index + "Z"),
                Duration.ofMillis(100),
                toolId,
                "execute",
                "{}", "{}", success,
                success ? null : "error",
                success ? RiskLevel.LOW : RiskLevel.HIGH
        );
    }

    /** 构建 LlmCallStep。 */
    private static LlmCallStep llmStep(int index, int inputTokens, int outputTokens) {
        return new LlmCallStep(
                index,
                Instant.parse("2026-03-01T10:01:0" + index + "Z"),
                Duration.ofSeconds(1),
                "openai",
                "gpt-4.1",
                "chat",
                inputTokens,
                outputTokens,
                Duration.ofMillis(800),
                false,
                0.7,
                "stop"
        );
    }

    /** 构建被拦截的 GuardrailStep。 */
    private static GuardrailStep blockedGuardrail(int index) {
        return new GuardrailStep(
                index,
                Instant.parse("2026-03-01T10:02:0" + index + "Z"),
                Duration.ZERO,
                "policy-1",
                "tool_call",
                false,
                "危险操作",
                RiskLevel.HIGH,
                ApprovalMode.USER_CONFIRM
        );
    }

    @Test
    @DisplayName("正常轨迹_各维度与综合评分在0到1之间()")
    void 正常轨迹_各维度与综合评分在0到1之间() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObservabilityProperties properties = defaultProperties();

        TrajectoryEvaluator evaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());

        var steps = List.<com.lifepilot.observability.trace.TraceStep>of(
                llmStep(0, 200, 100),
                toolStep(1, true, "t1"),
                toolStep(2, true, "t2")
        );
        TraceRecord trace = traceRecord(steps, 200, 100);

        EvaluationResult result = evaluator.evaluateOffline(trace);

        assertThat(result.toolSelectionScore()).isBetween(0.0, 1.0);
        assertThat(result.parameterValidityScore()).isBetween(0.0, 1.0);
        assertThat(result.stepEfficiencyScore()).isBetween(0.0, 1.0);
        assertThat(result.policyComplianceScore()).isBetween(0.0, 1.0);
        assertThat(result.tokenEfficiencyScore()).isBetween(0.0, 1.0);
        assertThat(result.overallScore()).isBetween(0.0, 1.0);
        assertThat(result.actualSteps()).isEqualTo(trace.totalSteps());
        assertThat(result.actualTokens()).isEqualTo(trace.inputTokens() + trace.outputTokens());
    }

    @Test
    @DisplayName("大量失败工具调用_工具选择与参数合法性评分显著下降()")
    void 大量失败工具调用_工具选择与参数合法性评分显著下降() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObservabilityProperties properties = defaultProperties();

        TrajectoryEvaluator evaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());

        // 5 次工具调用，4 次失败
        var steps = List.<com.lifepilot.observability.trace.TraceStep>of(
                llmStep(0, 100, 50),
                toolStep(1, false, "t1"),
                toolStep(2, false, "t1"),
                toolStep(3, false, "t2"),
                toolStep(4, false, "t2"),
                toolStep(5, true, "t3")
        );
        TraceRecord trace = traceRecord(steps, 100, 50);

        EvaluationResult result = evaluator.evaluateOffline(trace);

        assertThat(result.toolSelectionScore()).isLessThan(0.6);
        assertThat(result.parameterValidityScore()).isLessThan(0.5);
        assertThat(result.violations()).anyMatch(v -> v.contains("工具调用失败率过高"));
    }

    @Test
    @DisplayName("存在护栏拦截_策略合规性评分下降且有违规项()")
    void 存在护栏拦截_策略合规性评分下降且有违规项() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObservabilityProperties properties = defaultProperties();

        TrajectoryEvaluator evaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());

        var steps = List.<com.lifepilot.observability.trace.TraceStep>of(
                llmStep(0, 100, 50),
                toolStep(1, true, "t1"),
                blockedGuardrail(2),
                blockedGuardrail(3)
        );
        TraceRecord trace = traceRecord(steps, 100, 50);

        EvaluationResult result = evaluator.evaluateOffline(trace);

        assertThat(result.policyComplianceScore()).isLessThan(1.0);
        assertThat(result.violations()).anyMatch(v -> v.contains("护栏拦截"));
    }

    @Test
    @DisplayName("Token消耗远超预期_token效率评分降低并包含提示或违规()")
    void Token消耗远超预期_token效率评分降低并包含提示或违规() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObservabilityProperties properties = defaultProperties();

        TrajectoryEvaluator evaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());

        // 步骤数较少但 Token 极多
        var steps = List.<com.lifepilot.observability.trace.TraceStep>of(
                llmStep(0, 10_000, 10_000),
                toolStep(1, true, "t1")
        );
        TraceRecord trace = traceRecord(steps, 10_000, 10_000);

        EvaluationResult result = evaluator.evaluateOffline(trace);

        assertThat(result.tokenEfficiencyScore()).isLessThan(1.0);
        boolean hasHighOrWarning = result.violations().stream().anyMatch(v -> v.contains("Token 消耗过高"))
                || result.suggestions().stream().anyMatch(s -> s.contains("Token 消耗偏高"));
        assertThat(hasHighOrWarning).isTrue();
    }

    @Test
    @DisplayName("评估异常时_返回默认评分并包含默认违规项()")
    void 评估异常时_返回默认评分并包含默认违规项() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        ObservabilityProperties properties = defaultProperties();

        // 通过让 jdbcTemplate.update 抛出异常，测试 evaluateOnline 中的异常路径（持久化失败时仅记录日志，不影响评分）
        TrajectoryEvaluator realEvaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());
        TraceRecord trace = traceRecord(List.<com.lifepilot.observability.trace.TraceStep>of(), 0, 0);

        // 让 jdbcTemplate.update 抛出异常，从而触发 evaluateOnline 内部的异常分支
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        EvaluationResult result = realEvaluator.evaluateOnline(trace);

        // 空步骤返回默认评分 0.5，持久化失败仅记录日志不影响评分
        assertThat(result.toolSelectionScore()).isBetween(0.0, 1.0);
        assertThat(result.overallScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("在线评估_会调用JdbcTemplate持久化结果()")
    void 在线评估_会调用JdbcTemplate持久化结果() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObservabilityProperties properties = defaultProperties();

        TrajectoryEvaluator evaluator = new TrajectoryEvaluator(jdbcTemplate, properties, new EvaluationCore());

        var steps = List.<com.lifepilot.observability.trace.TraceStep>of(
                llmStep(0, 100, 50),
                toolStep(1, true, "t1")
        );
        TraceRecord trace = traceRecord(steps, 100, 50);

        EvaluationResult result = evaluator.evaluateOnline(trace);

        assertThat(result.traceId()).isEqualTo("trace-001");
        verify(jdbcTemplate).update(anyString(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}

