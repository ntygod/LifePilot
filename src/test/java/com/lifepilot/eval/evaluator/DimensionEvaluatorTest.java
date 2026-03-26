package com.lifepilot.eval.evaluator;

import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DimensionEvaluator 五维评估器单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class DimensionEvaluatorTest {

    /** 构建 ToolCallStep。 */
    private static ToolCallStep toolStep(int index, String toolId, String toolInput) {
        return new ToolCallStep(index, Instant.now(), Duration.ZERO,
                toolId, "execute", toolInput, "output", true, null, RiskLevel.LOW);
    }

    /** 构建 LlmCallStep（用于 Token 统计）。 */
    private static LlmCallStep llmStep(int index, int inputTokens, int outputTokens) {
        return new LlmCallStep(index, Instant.now(), Duration.ZERO,
                "test", "model", "eval", inputTokens, outputTokens, Duration.ZERO, false, 0.0, null);
    }

    /** 构建被阻止的 GuardrailStep。 */
    private static GuardrailStep blockedStep(int index, String reason) {
        return new GuardrailStep(index, Instant.now(), Duration.ZERO,
                "policy", "tool_call", false, reason, RiskLevel.HIGH, ApprovalMode.USER_CONFIRM);
    }

    /** 构建通过的 GuardrailStep。 */
    private static GuardrailStep passedStep(int index) {
        return new GuardrailStep(index, Instant.now(), Duration.ZERO,
                "policy", "tool_call", true, null, RiskLevel.LOW, ApprovalMode.AUTO);
    }

    /** 构建测试用 BenchmarkScenario。 */
    private static BenchmarkScenario buildScenario(List<String> expectedToolCalls,
                                                    int expectedStepCount,
                                                    int expectedTokenBudget) {
        return BenchmarkScenario.builder()
                .id("test-001").name("测试场景").userInput("测试输入")
                .expectedToolCalls(expectedToolCalls)
                .dimensionWeights(Map.of(
                        "toolSelection", 0.3, "parameterValidity", 0.2,
                        "stepEfficiency", 0.2, "policyCompliance", 0.2, "tokenEfficiency", 0.1))
                .timeoutSeconds(60).tags(List.of("test"))
                .expectedTokenBudget(expectedTokenBudget).expectedStepCount(expectedStepCount)
                .build();
    }

    // ─── ToolSelectionEvaluator 测试 ───

    @Nested
    class ToolSelectionEvaluatorTest {
        private ToolSelectionEvaluator evaluator;

        @BeforeEach
        void setUp() { evaluator = new ToolSelectionEvaluator(); }

        @Test
        void 维度名称正确() { assertEquals("toolSelection", evaluator.dimensionName()); }

        @Test
        void 完全匹配时评分为1() {
            List<TraceStep> steps = List.of(toolStep(0, "weather.query", null), toolStep(1, "calendar.add", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of("weather.query", "calendar.add"), 2, 1000));
            assertEquals(1.0, result.score(), 0.001);
            assertTrue(result.violations().isEmpty());
        }

        @Test
        void 两者均为空时评分为1() {
            List<TraceStep> steps = List.of(llmStep(0, 50, 50));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 1, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 部分匹配时评分为LCS相似度() {
            List<TraceStep> steps = List.of(toolStep(0, "weather.query", null), toolStep(1, "todo.add", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of("weather.query", "calendar.add", "todo.add"), 3, 1000));
            assertEquals(2.0 / 3.0, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }

        @Test
        void 完全不匹配时评分为0() {
            List<TraceStep> steps = List.of(toolStep(0, "a.tool", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of("b.tool"), 1, 1000));
            assertEquals(0.0, result.score(), 0.001);
        }

        @Test
        void 实际调用多于期望时有建议() {
            List<TraceStep> steps = List.of(toolStep(0, "a.tool", null), toolStep(1, "b.tool", null), toolStep(2, "c.tool", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of("a.tool"), 3, 1000));
            assertTrue(result.suggestions().stream().anyMatch(s -> s.contains("冗余调用")));
        }

        @Test
        void LCS算法_空序列() {
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of(), List.of()));
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of("a"), List.of()));
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of(), List.of("a")));
        }

        @Test
        void LCS算法_相同序列() {
            assertEquals(3, ToolSelectionEvaluator.computeLcs(List.of("a", "b", "c"), List.of("a", "b", "c")));
        }
    }

    // ─── ParameterValidityEvaluator 测试 ───

    @Nested
    class ParameterValidityEvaluatorTest {
        private DynamicToolRegistry toolRegistry;
        private ParameterValidityEvaluator evaluator;

        @BeforeEach
        void setUp() {
            toolRegistry = mock(DynamicToolRegistry.class);
            evaluator = new ParameterValidityEvaluator(toolRegistry);
        }

        private ToolContract buildTool(String id, JsonSchema schema) {
            return BuiltinTool.builder().id(id).name(id).description("测试工具")
                    .inputSchema(schema)
                    .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic(
                            com.lifepilot.tool.model.ToolSchedulingMode.PARALLEL_SAFE))
                    .executor(input -> null).build();
        }

        @Test
        void 维度名称正确() { assertEquals("parameterValidity", evaluator.dimensionName()); }

        @Test
        void 无工具调用时评分为1() {
            List<TraceStep> steps = List.of(llmStep(0, 50, 50));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 1, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 工具未注册时计为无效() {
            when(toolRegistry.resolve("unknown.tool")).thenReturn(Optional.empty());
            List<TraceStep> steps = List.of(toolStep(0, "unknown.tool", "{}"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("unknown.tool"), 1, 1000));
            assertEquals(0.0, result.score(), 0.001);
            assertTrue(result.violations().stream().anyMatch(v -> v.contains("未在注册中心找到")));
        }

        @Test
        void 空Schema时视为通过() {
            var tool = buildTool("empty.tool", JsonSchema.empty());
            when(toolRegistry.resolve("empty.tool")).thenReturn(Optional.of(tool));
            List<TraceStep> steps = List.of(toolStep(0, "empty.tool", "{}"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("empty.tool"), 1, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 参数校验通过时评分为1() {
            var schema = JsonSchema.of(Map.of(
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city")));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));
            List<TraceStep> steps = List.of(toolStep(0, "weather.query", "{\"city\":\"北京\"}"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("weather.query"), 1, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 参数校验失败时评分降低() {
            var schema = JsonSchema.of(Map.of(
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city")));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));
            // 缺少 required 字段 city
            List<TraceStep> steps = List.of(toolStep(0, "weather.query", "{}"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("weather.query"), 1, 1000));
            assertEquals(0.0, result.score(), 0.001);
        }

        @Test
        void 无效JSON输入时计为无效() {
            var schema = JsonSchema.of(Map.of("properties", Map.of("x", Map.of("type", "string"))));
            var tool = buildTool("test.tool", schema);
            when(toolRegistry.resolve("test.tool")).thenReturn(Optional.of(tool));
            List<TraceStep> steps = List.of(toolStep(0, "test.tool", "not-json"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("test.tool"), 1, 1000));
            assertEquals(0.0, result.score(), 0.001);
        }

        @Test
        void 混合有效和无效调用时评分为比例() {
            var schema = JsonSchema.of(Map.of(
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city")));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));
            // 第一个通过，第二个缺少 required 字段
            List<TraceStep> steps = List.of(
                    toolStep(0, "weather.query", "{\"city\":\"北京\"}"),
                    toolStep(1, "weather.query", "{}"));
            var result = evaluator.evaluate(steps, buildScenario(List.of("weather.query", "weather.query"), 2, 1000));
            assertEquals(0.5, result.score(), 0.001);
        }
    }

    // ─── StepEfficiencyEvaluator 测试 ───

    @Nested
    class StepEfficiencyEvaluatorTest {
        private StepEfficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() { evaluator = new StepEfficiencyEvaluator(); }

        @Test
        void 维度名称正确() { assertEquals("stepEfficiency", evaluator.dimensionName()); }

        @Test
        void 空步骤时评分为1() {
            var result = evaluator.evaluate(List.of(), buildScenario(List.of(), 0, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 步骤数等于期望时评分为1() {
            List<TraceStep> steps = List.of(toolStep(0, "t1", null), toolStep(1, "t2", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 2, 1000));
            assertEquals(1.0, result.score(), 0.001);
            assertTrue(result.violations().isEmpty());
        }

        @Test
        void 步骤数少于期望时评分为1() {
            List<TraceStep> steps = List.of(toolStep(0, "t1", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 3, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 步骤数多于期望时评分降低() {
            List<TraceStep> steps = List.of(
                    toolStep(0, "t1", null), toolStep(1, "t2", null),
                    toolStep(2, "t3", null), toolStep(3, "t4", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 2, 1000));
            assertEquals(0.5, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
            assertFalse(result.suggestions().isEmpty());
        }
    }

    // ─── PolicyComplianceEvaluator 测试 ───

    @Nested
    class PolicyComplianceEvaluatorTest {
        private PolicyComplianceEvaluator evaluator;

        @BeforeEach
        void setUp() { evaluator = new PolicyComplianceEvaluator(); }

        @Test
        void 维度名称正确() { assertEquals("policyCompliance", evaluator.dimensionName()); }

        @Test
        void 空步骤时评分为1() {
            var result = evaluator.evaluate(List.of(), buildScenario(List.of(), 0, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 无阻止步骤时评分为1() {
            List<TraceStep> steps = List.of(passedStep(0), passedStep(1), toolStep(2, "t1", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 3, 1000));
            assertEquals(1.0, result.score(), 0.001);
            assertTrue(result.violations().isEmpty());
        }

        @Test
        void 有阻止步骤时评分降低() {
            List<TraceStep> steps = List.of(
                    toolStep(0, "t1", null),
                    blockedStep(1, "危险操作"),
                    toolStep(2, "t2", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 3, 1000));
            // 1 blocked / 3 total → 1.0 - 1/3 ≈ 0.667
            assertEquals(1.0 - 1.0 / 3.0, result.score(), 0.001);
            assertTrue(result.violations().stream().anyMatch(v -> v.contains("危险操作")));
        }

        @Test
        void 全部阻止时评分为0() {
            List<TraceStep> steps = List.of(blockedStep(0, "原因A"), blockedStep(1, "原因B"));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 2, 1000));
            assertEquals(0.0, result.score(), 0.001);
        }
    }

    // ─── TokenEfficiencyEvaluator 测试 ───

    @Nested
    class TokenEfficiencyEvaluatorTest {
        private TokenEfficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() { evaluator = new TokenEfficiencyEvaluator(); }

        @Test
        void 维度名称正确() { assertEquals("tokenEfficiency", evaluator.dimensionName()); }

        @Test
        void 无LLM调用时评分为1() {
            List<TraceStep> steps = List.of(toolStep(0, "t1", null));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 1, 1000));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void Token消耗在预算内时评分为1() {
            // 200 input + 100 output = 300 total, budget = 500
            List<TraceStep> steps = List.of(llmStep(0, 200, 100));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 1, 500));
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void Token消耗超预算时评分降低() {
            // 600 input + 400 output = 1000 total, budget = 500
            List<TraceStep> steps = List.of(llmStep(0, 600, 400));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 1, 500));
            assertEquals(0.5, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }

        @Test
        void 多个LLM调用累加Token() {
            // (100+50) + (200+150) = 500 total, budget = 500
            List<TraceStep> steps = List.of(llmStep(0, 100, 50), llmStep(1, 200, 150));
            var result = evaluator.evaluate(steps, buildScenario(List.of(), 2, 500));
            assertEquals(1.0, result.score(), 0.001);
        }
    }

    // ─── sealed interface 穷举匹配测试 ───

    @Test
    void sealed_interface_switch穷举匹配() {
        // 验证所有 TraceStep 子类型都能被正确创建和识别
        List<TraceStep> allTypes = List.of(
                toolStep(0, "tool", null),
                llmStep(1, 100, 50),
                blockedStep(2, "reason"),
                passedStep(3));
        assertEquals(4, allTypes.size());
        assertInstanceOf(ToolCallStep.class, allTypes.get(0));
        assertInstanceOf(LlmCallStep.class, allTypes.get(1));
        assertInstanceOf(GuardrailStep.class, allTypes.get(2));
        assertInstanceOf(GuardrailStep.class, allTypes.get(3));
    }
}
