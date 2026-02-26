package com.lifepilot.eval.evaluator;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    /** 构建测试用 TraceStep 的辅助方法。 */
    private static TraceStep buildStep(int index, String toolId, String toolInput,
                                       boolean blocked, String blockReason, int tokensUsed) {
        return TraceStep.builder()
                .traceId("trace-001")
                .stepIndex(index)
                .phaseBefore(AgentPhase.EXECUTING)
                .phaseAfter(AgentPhase.EXECUTING)
                .action(new Action.ToolResult(
                        toolId != null ? toolId : "unknown", true, "ok", tokensUsed, 100L, false))
                .toolId(toolId)
                .toolInput(toolInput)
                .toolOutput("output")
                .blocked(blocked)
                .blockReason(blockReason)
                .tokensUsed(tokensUsed)
                .latencyMs(100L)
                .timestamp(Instant.now())
                .build();
    }

    /** 构建测试用 BenchmarkScenario 的辅助方法。 */
    private static BenchmarkScenario buildScenario(List<String> expectedToolCalls,
                                                    int expectedStepCount,
                                                    int expectedTokenBudget) {
        return BenchmarkScenario.builder()
                .id("test-001")
                .name("测试场景")
                .userInput("测试输入")
                .expectedToolCalls(expectedToolCalls)
                .dimensionWeights(Map.of(
                        "toolSelection", 0.3,
                        "parameterValidity", 0.2,
                        "stepEfficiency", 0.2,
                        "policyCompliance", 0.2,
                        "tokenEfficiency", 0.1))
                .timeoutSeconds(60)
                .tags(List.of("test"))
                .expectedTokenBudget(expectedTokenBudget)
                .expectedStepCount(expectedStepCount)
                .build();
    }

    // ─────────────────────────────────────────────
    //  ToolSelectionEvaluator 测试
    // ─────────────────────────────────────────────

    @Nested
    class ToolSelectionEvaluatorTest {

        private ToolSelectionEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new ToolSelectionEvaluator();
        }

        @Test
        void 维度名称正确() {
            assertEquals("toolSelection", evaluator.dimensionName());
        }

        @Test
        void 完全匹配时评分为1() {
            var steps = List.of(
                    buildStep(0, "weather.query", null, false, null, 100),
                    buildStep(1, "calendar.add", null, false, null, 100));
            var scenario = buildScenario(List.of("weather.query", "calendar.add"), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
            assertTrue(result.violations().isEmpty());
        }

        @Test
        void 两者均为空时评分为1() {
            var steps = List.of(
                    buildStep(0, null, null, false, null, 100));
            var scenario = buildScenario(List.of(), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 部分匹配时评分为LCS相似度() {
            var steps = List.of(
                    buildStep(0, "weather.query", null, false, null, 100),
                    buildStep(1, "todo.add", null, false, null, 100));
            var scenario = buildScenario(
                    List.of("weather.query", "calendar.add", "todo.add"), 3, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // LCS = ["weather.query", "todo.add"] = 2, max(3, 2) = 3
            assertEquals(2.0 / 3.0, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }

        @Test
        void 完全不匹配时评分为0() {
            var steps = List.of(
                    buildStep(0, "a.tool", null, false, null, 100));
            var scenario = buildScenario(List.of("b.tool"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(0.0, result.score(), 0.001);
        }

        @Test
        void 实际调用多于期望时有建议() {
            var steps = List.of(
                    buildStep(0, "a.tool", null, false, null, 100),
                    buildStep(1, "b.tool", null, false, null, 100),
                    buildStep(2, "c.tool", null, false, null, 100));
            var scenario = buildScenario(List.of("a.tool"), 3, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertTrue(result.suggestions().stream()
                    .anyMatch(s -> s.contains("冗余调用")));
        }

        @Test
        void LCS算法_空序列() {
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of(), List.of()));
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of("a"), List.of()));
            assertEquals(0, ToolSelectionEvaluator.computeLcs(List.of(), List.of("a")));
        }

        @Test
        void LCS算法_相同序列() {
            var seq = List.of("a", "b", "c");
            assertEquals(3, ToolSelectionEvaluator.computeLcs(seq, seq));
        }
    }

    // ─────────────────────────────────────────────
    //  ParameterValidityEvaluator 测试
    // ─────────────────────────────────────────────

    @Nested
    class ParameterValidityEvaluatorTest {

        private DynamicToolRegistry toolRegistry;
        private ParameterValidityEvaluator evaluator;

        @BeforeEach
        void setUp() {
            toolRegistry = mock(DynamicToolRegistry.class);
            evaluator = new ParameterValidityEvaluator(toolRegistry);
        }

        /** 构建测试用 BuiltinTool（sealed interface 不可 mock）。 */
        private ToolContract buildTool(String id, JsonSchema schema) {
            return BuiltinTool.builder()
                    .id(id)
                    .name(id)
                    .description("测试工具")
                    .inputSchema(schema)
                    .executor(input -> null)
                    .build();
        }

        @Test
        void 维度名称正确() {
            assertEquals("parameterValidity", evaluator.dimensionName());
        }

        @Test
        void 无工具调用时评分为1() {
            var steps = List.of(
                    buildStep(0, null, null, false, null, 100));
            var scenario = buildScenario(List.of(), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 工具未注册时计为无效() {
            when(toolRegistry.resolve("unknown.tool")).thenReturn(Optional.empty());

            var steps = List.of(
                    buildStep(0, "unknown.tool", "{}", false, null, 100));
            var scenario = buildScenario(List.of("unknown.tool"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(0.0, result.score(), 0.001);
            assertTrue(result.violations().stream()
                    .anyMatch(v -> v.contains("未在注册中心找到")));
        }

        @Test
        void 空Schema时视为通过() {
            var tool = buildTool("test.tool", JsonSchema.empty());
            when(toolRegistry.resolve("test.tool")).thenReturn(Optional.of(tool));

            var steps = List.of(
                    buildStep(0, "test.tool", "{\"key\":\"value\"}", false, null, 100));
            var scenario = buildScenario(List.of("test.tool"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 参数校验通过时评分为1() {
            var schema = JsonSchema.of(Map.of(
                    "required", List.of("city"),
                    "properties", Map.of("city", Map.of("type", "string"))));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));

            var steps = List.of(
                    buildStep(0, "weather.query", "{\"city\":\"北京\"}", false, null, 100));
            var scenario = buildScenario(List.of("weather.query"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 参数校验失败时评分降低() {
            var schema = JsonSchema.of(Map.of(
                    "required", List.of("city"),
                    "properties", Map.of("city", Map.of("type", "string"))));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));

            // 缺少 required 字段 "city"
            var steps = List.of(
                    buildStep(0, "weather.query", "{\"date\":\"2026-01-01\"}", false, null, 100));
            var scenario = buildScenario(List.of("weather.query"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(0.0, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }

        @Test
        void 无效JSON输入时计为无效() {
            var tool = buildTool("test.tool", JsonSchema.of(Map.of("required", List.of("x"))));
            when(toolRegistry.resolve("test.tool")).thenReturn(Optional.of(tool));

            var steps = List.of(
                    buildStep(0, "test.tool", "not-valid-json", false, null, 100));
            var scenario = buildScenario(List.of("test.tool"), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(0.0, result.score(), 0.001);
            assertTrue(result.violations().stream()
                    .anyMatch(v -> v.contains("JSON 解析失败")));
        }

        @Test
        void 混合有效和无效调用时评分为比例() {
            var schema = JsonSchema.of(Map.of(
                    "required", List.of("city"),
                    "properties", Map.of("city", Map.of("type", "string"))));
            var tool = buildTool("weather.query", schema);
            when(toolRegistry.resolve("weather.query")).thenReturn(Optional.of(tool));

            var steps = List.of(
                    buildStep(0, "weather.query", "{\"city\":\"北京\"}", false, null, 100),
                    buildStep(1, "weather.query", "{\"date\":\"2026-01-01\"}", false, null, 100));
            var scenario = buildScenario(List.of("weather.query", "weather.query"), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // 1 valid / 2 total = 0.5
            assertEquals(0.5, result.score(), 0.001);
        }
    }

    // ─────────────────────────────────────────────
    //  StepEfficiencyEvaluator 测试
    // ─────────────────────────────────────────────

    @Nested
    class StepEfficiencyEvaluatorTest {

        private StepEfficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new StepEfficiencyEvaluator();
        }

        @Test
        void 维度名称正确() {
            assertEquals("stepEfficiency", evaluator.dimensionName());
        }

        @Test
        void 无步骤时评分为1() {
            var scenario = buildScenario(List.of(), 3, 1000);

            var result = evaluator.evaluate(List.of(), scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际步骤等于期望时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100),
                    buildStep(1, "b", null, false, null, 100),
                    buildStep(2, "c", null, false, null, 100));
            var scenario = buildScenario(List.of(), 3, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际步骤少于期望时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100));
            var scenario = buildScenario(List.of(), 3, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // min(3/1, 1.0) = 1.0
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际步骤多于期望时评分降低() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100),
                    buildStep(1, "b", null, false, null, 100),
                    buildStep(2, "c", null, false, null, 100),
                    buildStep(3, "d", null, false, null, 100),
                    buildStep(4, "e", null, false, null, 100),
                    buildStep(5, "f", null, false, null, 100));
            var scenario = buildScenario(List.of(), 3, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // min(3/6, 1.0) = 0.5
            assertEquals(0.5, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    //  PolicyComplianceEvaluator 测试
    // ─────────────────────────────────────────────

    @Nested
    class PolicyComplianceEvaluatorTest {

        private PolicyComplianceEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new PolicyComplianceEvaluator();
        }

        @Test
        void 维度名称正确() {
            assertEquals("policyCompliance", evaluator.dimensionName());
        }

        @Test
        void 无步骤时评分为1() {
            var scenario = buildScenario(List.of(), 0, 1000);

            var result = evaluator.evaluate(List.of(), scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 无阻止步骤时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100),
                    buildStep(1, "b", null, false, null, 100));
            var scenario = buildScenario(List.of(), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
            assertTrue(result.violations().isEmpty());
        }

        @Test
        void 全部阻止时评分为0() {
            var steps = List.of(
                    buildStep(0, "a", null, true, "危险操作", 100),
                    buildStep(1, "b", null, true, "权限不足", 100));
            var scenario = buildScenario(List.of(), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(0.0, result.score(), 0.001);
            assertEquals(2, result.violations().size());
        }

        @Test
        void 部分阻止时评分为比例() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100),
                    buildStep(1, "b", null, true, "危险操作", 100),
                    buildStep(2, "c", null, false, null, 100),
                    buildStep(3, "d", null, true, null, 100));
            var scenario = buildScenario(List.of(), 4, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // 1.0 - 2/4 = 0.5
            assertEquals(0.5, result.score(), 0.001);
            assertEquals(2, result.violations().size());
        }

        @Test
        void 阻止原因为null时显示未知原因() {
            var steps = List.of(
                    buildStep(0, "a", null, true, null, 100));
            var scenario = buildScenario(List.of(), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertTrue(result.violations().stream()
                    .anyMatch(v -> v.contains("未知原因")));
        }
    }

    // ─────────────────────────────────────────────
    //  TokenEfficiencyEvaluator 测试
    // ─────────────────────────────────────────────

    @Nested
    class TokenEfficiencyEvaluatorTest {

        private TokenEfficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new TokenEfficiencyEvaluator();
        }

        @Test
        void 维度名称正确() {
            assertEquals("tokenEfficiency", evaluator.dimensionName());
        }

        @Test
        void 无Token消耗时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 0));
            var scenario = buildScenario(List.of(), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际Token等于预算时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 500),
                    buildStep(1, "b", null, false, null, 500));
            var scenario = buildScenario(List.of(), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际Token少于预算时评分为1() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 200));
            var scenario = buildScenario(List.of(), 1, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // min(1000/200, 1.0) = 1.0
            assertEquals(1.0, result.score(), 0.001);
        }

        @Test
        void 实际Token超过预算时评分降低() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 1000),
                    buildStep(1, "b", null, false, null, 1000));
            var scenario = buildScenario(List.of(), 2, 1000);

            var result = evaluator.evaluate(steps, scenario);

            // min(1000/2000, 1.0) = 0.5
            assertEquals(0.5, result.score(), 0.001);
            assertFalse(result.violations().isEmpty());
        }

        @Test
        void 多步骤Token累加正确() {
            var steps = List.of(
                    buildStep(0, "a", null, false, null, 100),
                    buildStep(1, "b", null, false, null, 200),
                    buildStep(2, "c", null, false, null, 300));
            var scenario = buildScenario(List.of(), 3, 300);

            var result = evaluator.evaluate(steps, scenario);

            // actualTokens = 600, min(300/600, 1.0) = 0.5
            assertEquals(0.5, result.score(), 0.001);
        }
    }

    // ─────────────────────────────────────────────
    //  sealed interface 穷举匹配测试
    // ─────────────────────────────────────────────

    @Test
    void sealed_interface_switch穷举匹配() {
        var toolRegistry = mock(DynamicToolRegistry.class);
        List<DimensionEvaluator> evaluators = List.of(
                new ToolSelectionEvaluator(),
                new ParameterValidityEvaluator(toolRegistry),
                new StepEfficiencyEvaluator(),
                new PolicyComplianceEvaluator(),
                new TokenEfficiencyEvaluator());

        for (DimensionEvaluator evaluator : evaluators) {
            // 验证 switch 穷举匹配编译通过
            String name = switch (evaluator) {
                case ToolSelectionEvaluator _ -> "toolSelection";
                case ParameterValidityEvaluator _ -> "parameterValidity";
                case StepEfficiencyEvaluator _ -> "stepEfficiency";
                case PolicyComplianceEvaluator _ -> "policyCompliance";
                case TokenEfficiencyEvaluator _ -> "tokenEfficiency";
            };
            assertEquals(evaluator.dimensionName(), name);
        }
    }
}
