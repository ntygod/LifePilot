package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.memory.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轨迹质量评估器单元测试。
 *
 * <p>覆盖 {@link TrajectoryQualityAssessor#assess} 的所有评估维度：
 * goal 清晰度、轨迹完整性、工具成功率、任务成功判定、Eval 门控宽松以及综合门控逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
class TrajectoryQualityAssessor_单元测试 {

    private MemoryProperties properties;
    private TrajectoryQualityAssessor assessor;

    /** 默认预算，测试中不关注预算本身。 */
    private static final Budget DEFAULT_BUDGET = Budget.builder()
            .maxTokens(10000).tokensUsed(0).tokensReserved(0)
            .maxSteps(20).stepsUsed(0)
            .maxDuration(Duration.ofMinutes(5)).elapsed(Duration.ZERO)
            .build();

    @BeforeEach
    void 初始化() {
        properties = new MemoryProperties();
        // 使用默认配置：minToolSuccessRatio=0.3, evalQualityRelaxFactor=0.5
        assessor = new TrajectoryQualityAssessor(properties);
    }

    // ==================== 辅助方法 ====================

    /** 构建基础正常完成的状态（含 Answer 步骤、finalOutput 非空、terminationReason 为 null）。 */
    private ReactAgentState.ReactAgentStateBuilder 正常完成状态() {
        return ReactAgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal("完成单元测试覆盖")
                .source(InteractionSource.system("test"))
                .steps(List.of(
                        new ReactStep.Thought("分析需求"),
                        new ReactStep.Answer("测试完成")
                ))
                .stepCount(2)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(DEFAULT_BUDGET)
                .depth(0)
                .done(true)
                .finalOutput("测试已完成")
                .terminationReason(null)
                .completionMode(CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false);
    }

    /** 构建包含工具调用的状态。 */
    private ReactAgentState 含工具调用状态(int 成功数, int 失败数) {
        var steps = new java.util.ArrayList<ReactStep>();
        for (int i = 0; i < 成功数; i++) {
            steps.add(new ReactStep.ToolCall("tool-" + i, "工具" + i, "{}", 100));
            steps.add(new ReactStep.Observation("tool-" + i, "工具" + i, true, "成功", 50));
        }
        for (int i = 0; i < 失败数; i++) {
            steps.add(new ReactStep.ToolCall("fail-" + i, "失败工具" + i, "{}", 100));
            steps.add(new ReactStep.Observation("fail-" + i, "失败工具" + i, false, "失败", 50));
        }
        steps.add(new ReactStep.Answer("完成"));

        return 正常完成状态()
                .steps(steps)
                .stepCount(steps.size())
                .build();
    }

    /** 构建 EvalResult 便捷方法。 */
    private EvalResult 构建评估结果(double overallScore, Map<String, Double> dimensionScores) {
        return EvalResult.builder()
                .evalId("eval-1")
                .traceId("trace-1")
                .scenarioId("scenario-1")
                .dimensionScores(dimensionScores)
                .overallScore(overallScore)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId("run-1")
                .build();
    }

    // ==================== Goal 清晰度 ====================

    @Nested
    class Goal清晰度 {

        @Test
        void goal为null时质量不通过() {
            var state = 正常完成状态().goal(null).build();
            var report = assessor.assess(state);

            assertFalse(report.goalClarity(), "goal 为 null 应判定不清晰");
            assertFalse(report.qualityPassed(), "goal 不清晰时综合质量应不通过");
        }

        @Test
        void goal为空字符串时质量不通过() {
            var state = 正常完成状态().goal("").build();
            var report = assessor.assess(state);

            assertFalse(report.goalClarity(), "空字符串 goal 长度 < 2 应判定不清晰");
            assertFalse(report.qualityPassed());
        }

        @Test
        void goal为单字符时质量不通过() {
            var state = 正常完成状态().goal("A").build();
            var report = assessor.assess(state);

            assertFalse(report.goalClarity(), "单字符 goal 长度 < 2 应判定不清晰");
            assertFalse(report.qualityPassed());
        }

        @Test
        void goal恰好两个字符时质量通过() {
            var state = 正常完成状态().goal("AB").build();
            var report = assessor.assess(state);

            assertTrue(report.goalClarity(), "goal 长度 == 2 应判定清晰");
        }

        @Test
        void goal为正常中文句子时质量通过() {
            var state = 正常完成状态().goal("帮我写一篇技术博客").build();
            var report = assessor.assess(state);

            assertTrue(report.goalClarity());
        }
    }

    // ==================== 轨迹完整性 ====================

    @Nested
    class 轨迹完整性 {

        @Test
        void 含Answer步骤且finalOutput非空时完整() {
            var state = 正常完成状态().build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness());
        }

        @Test
        void 仅含Answer步骤而finalOutput为null时仍完整() {
            var state = 正常完成状态()
                    .finalOutput(null)
                    .steps(List.of(new ReactStep.Answer("回答")))
                    .stepCount(1)
                    .build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness(), "有 Answer 步骤即视为完整");
        }

        @Test
        void 无Answer步骤但finalOutput非空时仍完整() {
            var state = 正常完成状态()
                    .steps(List.of(new ReactStep.Thought("思考")))
                    .stepCount(1)
                    .finalOutput("直接输出")
                    .build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness(), "finalOutput 非空即视为完整");
        }

        @Test
        void 无Answer步骤且finalOutput为null时不完整() {
            var state = 正常完成状态()
                    .steps(List.of(new ReactStep.Thought("思考")))
                    .stepCount(1)
                    .finalOutput(null)
                    .build();
            var report = assessor.assess(state);

            assertFalse(report.trajectoryCompleteness(), "既无 Answer 又无 finalOutput 应判定不完整");
            assertFalse(report.qualityPassed());
        }

        @Test
        void 空步骤列表且finalOutput为null时不完整() {
            var state = 正常完成状态()
                    .steps(List.of())
                    .stepCount(0)
                    .finalOutput(null)
                    .build();
            var report = assessor.assess(state);

            assertFalse(report.trajectoryCompleteness());
            assertFalse(report.qualityPassed());
        }

        @Test
        void 空步骤列表但finalOutput非空时完整() {
            var state = 正常完成状态()
                    .steps(List.of())
                    .stepCount(0)
                    .finalOutput("直接返回")
                    .build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness());
            assertTrue(report.qualityPassed(), "空步骤但有 finalOutput 且其他条件满足应通过");
        }
    }

    // ==================== 工具成功率 ====================

    @Nested
    class 工具成功率 {

        @Test
        void 无工具调用时成功率为零() {
            var state = 正常完成状态().build();
            var report = assessor.assess(state);

            assertEquals(0f, report.toolSuccessRatio(), "无 Observation 步骤时成功率应为 0");
            // 但 totalObservations==0 时门控条件不生效，质量仍可通过
            assertTrue(report.qualityPassed());
        }

        @Test
        void 全部工具调用成功时成功率为一() {
            var state = 含工具调用状态(5, 0);
            var report = assessor.assess(state);

            assertEquals(1.0f, report.toolSuccessRatio(), 0.001f);
            assertTrue(report.qualityPassed());
        }

        @Test
        void 全部工具调用失败时成功率为零() {
            var state = 含工具调用状态(0, 3);
            var report = assessor.assess(state);

            assertEquals(0f, report.toolSuccessRatio(), 0.001f);
            assertFalse(report.qualityPassed(), "成功率 0 < 门控值 0.3 应不通过");
        }

        @Test
        void 成功率恰好等于门控值时通过() {
            // 3 成功 + 7 失败 = 成功率 0.3，等于默认门控值
            var state = 含工具调用状态(3, 7);
            var report = assessor.assess(state);

            assertEquals(0.3f, report.toolSuccessRatio(), 0.001f);
            assertTrue(report.qualityPassed(), "成功率 == 门控值时应通过（>= 判定）");
        }

        @Test
        void 成功率略低于门控值时不通过() {
            // 2 成功 + 8 失败 = 成功率 0.2
            var state = 含工具调用状态(2, 8);
            var report = assessor.assess(state);

            assertEquals(0.2f, report.toolSuccessRatio(), 0.001f);
            assertFalse(report.qualityPassed(), "成功率 0.2 < 门控值 0.3 应不通过");
        }

        @Test
        void 自定义门控值生效() {
            properties.getExperience().setMinToolSuccessRatio(0.8f);
            assessor = new TrajectoryQualityAssessor(properties);

            // 7 成功 + 3 失败 = 成功率 0.7
            var state = 含工具调用状态(7, 3);
            var report = assessor.assess(state);

            assertEquals(0.7f, report.toolSuccessRatio(), 0.001f);
            assertFalse(report.qualityPassed(), "成功率 0.7 < 自定义门控值 0.8 应不通过");
        }
    }

    // ==================== 任务成功判定 ====================

    @Nested
    class 任务成功判定 {

        @Test
        void terminationReason为null时任务成功() {
            var state = 正常完成状态().terminationReason(null).build();
            var report = assessor.assess(state);

            assertTrue(report.taskSuccess());
        }

        @Test
        void terminationReason非null时任务失败() {
            var state = 正常完成状态().terminationReason("Token 预算耗尽").build();
            var report = assessor.assess(state);

            assertFalse(report.taskSuccess());
            // 注意：taskSuccess 不影响 qualityPassed 的门控逻辑
        }
    }

    // ==================== 挂起状态门控 ====================

    @Nested
    class 挂起状态门控 {

        @Test
        void 未挂起状态时不影响质量() {
            var state = 正常完成状态().suspended(false).build();
            var report = assessor.assess(state);

            assertTrue(report.qualityPassed());
        }

        @Test
        void 挂起状态时质量不通过() {
            var state = 正常完成状态().suspended(true).build();
            var report = assessor.assess(state);

            assertFalse(report.qualityPassed(), "挂起状态的轨迹不适合提炼经验");
        }
    }

    // ==================== 步骤数 ====================

    @Nested
    class 步骤数报告 {

        @Test
        void 零步骤时totalSteps为零() {
            var state = 正常完成状态()
                    .steps(List.of())
                    .stepCount(0)
                    .build();
            var report = assessor.assess(state);

            assertEquals(0, report.totalSteps());
        }

        @Test
        void 多步骤时totalSteps正确反映() {
            var steps = List.<ReactStep>of(
                    new ReactStep.Thought("思考1"),
                    new ReactStep.ToolCall("t1", "工具1", "{}", 50),
                    new ReactStep.Observation("t1", "工具1", true, "成功", 10),
                    new ReactStep.Answer("完成")
            );
            var state = 正常完成状态()
                    .steps(steps)
                    .stepCount(4)
                    .build();
            var report = assessor.assess(state);

            assertEquals(4, report.totalSteps());
        }
    }

    // ==================== Eval 集成 ====================

    @Nested
    class Eval集成 {

        @Test
        void 无EvalResult时评估维度为null() {
            var state = 正常完成状态().build();
            var report = assessor.assess(state, null);

            assertNull(report.evalOverallScore());
            assertNull(report.evalDimensionScores());
        }

        @Test
        void 有EvalResult时评估维度正确传递() {
            var state = 正常完成状态().build();
            var dimensions = Map.of("accuracy", 0.9, "fluency", 0.85);
            var evalResult = 构建评估结果(0.88, dimensions);

            var report = assessor.assess(state, evalResult);

            assertNotNull(report.evalOverallScore());
            assertEquals(0.88, report.evalOverallScore(), 0.001);
            assertEquals(dimensions, report.evalDimensionScores());
        }

        @Test
        void Eval高分时放宽工具成功率门控() {
            // 默认 minToolSuccessRatio=0.3, evalQualityRelaxFactor=0.5
            // 放宽后 effectiveMinRatio = 0.3 * 0.5 = 0.15
            // 2 成功 + 8 失败 = 成功率 0.2，正常不通过但放宽后通过
            var state = 含工具调用状态(2, 8);
            var evalResult = 构建评估结果(0.75, Map.of("accuracy", 0.75));

            var report = assessor.assess(state, evalResult);

            assertEquals(0.2f, report.toolSuccessRatio(), 0.001f);
            assertTrue(report.qualityPassed(), "Eval 高分（>= 0.7）应放宽工具成功率门控");
        }

        @Test
        void Eval恰好0点7分时触发放宽() {
            // 0.3 * 0.5 = 0.15，成功率 0.2 >= 0.15 通过
            var state = 含工具调用状态(2, 8);
            var evalResult = 构建评估结果(0.7, Map.of());

            var report = assessor.assess(state, evalResult);

            assertTrue(report.qualityPassed(), "overallScore == 0.7 应恰好触发放宽");
        }

        @Test
        void Eval低于0点7分时不放宽() {
            // 不放宽，minRatio 仍然是 0.3
            // 2 成功 + 8 失败 = 成功率 0.2 < 0.3 不通过
            var state = 含工具调用状态(2, 8);
            var evalResult = 构建评估结果(0.69, Map.of());

            var report = assessor.assess(state, evalResult);

            assertFalse(report.qualityPassed(), "overallScore < 0.7 时不应放宽门控");
        }

        @Test
        void Eval高分但成功率仍低于放宽后门控时不通过() {
            // 放宽后 effectiveMinRatio = 0.3 * 0.5 = 0.15
            // 1 成功 + 9 失败 = 成功率 0.1 < 0.15 不通过
            var state = 含工具调用状态(1, 9);
            var evalResult = 构建评估结果(0.9, Map.of());

            var report = assessor.assess(state, evalResult);

            assertEquals(0.1f, report.toolSuccessRatio(), 0.001f);
            assertFalse(report.qualityPassed(), "即使 Eval 高分，成功率低于放宽后门控仍不通过");
        }
    }

    // ==================== 综合门控判定 ====================

    @Nested
    class 综合门控判定 {

        @Test
        void 所有条件满足时通过() {
            var state = 含工具调用状态(5, 0);
            var report = assessor.assess(state);

            assertTrue(report.goalClarity());
            assertTrue(report.trajectoryCompleteness());
            assertTrue(report.taskSuccess());
            assertTrue(report.qualityPassed());
        }

        @Test
        void goalClarity失败导致综合不通过() {
            var state = 正常完成状态().goal("").build();
            var report = assessor.assess(state);

            assertFalse(report.goalClarity());
            assertFalse(report.qualityPassed());
        }

        @Test
        void trajectoryCompleteness失败导致综合不通过() {
            var state = 正常完成状态()
                    .steps(List.of(new ReactStep.Thought("思考")))
                    .stepCount(1)
                    .finalOutput(null)
                    .build();
            var report = assessor.assess(state);

            assertFalse(report.trajectoryCompleteness());
            assertFalse(report.qualityPassed());
        }

        @Test
        void suspended为true导致综合不通过() {
            var state = 正常完成状态().suspended(true).build();
            var report = assessor.assess(state);

            assertFalse(report.qualityPassed());
        }

        @Test
        void toolSuccessRatio低于门控导致综合不通过() {
            var state = 含工具调用状态(1, 9); // 成功率 0.1
            var report = assessor.assess(state);

            assertFalse(report.qualityPassed());
        }

        @Test
        void taskSuccess不影响综合门控() {
            // terminationReason 非 null -> taskSuccess=false，但 qualityPassed 应不受影响
            var state = 正常完成状态().terminationReason("预算耗尽").build();
            var report = assessor.assess(state);

            assertFalse(report.taskSuccess());
            assertTrue(report.qualityPassed(), "taskSuccess 不是 qualityPassed 的门控条件");
        }

        @Test
        void 多条件同时失败时综合不通过() {
            var state = 正常完成状态()
                    .goal("")
                    .steps(List.of(new ReactStep.Thought("思考")))
                    .stepCount(1)
                    .finalOutput(null)
                    .suspended(true)
                    .build();
            var report = assessor.assess(state);

            assertFalse(report.goalClarity());
            assertFalse(report.trajectoryCompleteness());
            assertFalse(report.qualityPassed());
        }
    }

    // ==================== assess 单参数重载 ====================

    @Nested
    class 单参数重载 {

        @Test
        void 单参数assess等价于evalResult传null() {
            var state = 正常完成状态().build();

            var report1 = assessor.assess(state);
            var report2 = assessor.assess(state, null);

            assertEquals(report1.goalClarity(), report2.goalClarity());
            assertEquals(report1.trajectoryCompleteness(), report2.trajectoryCompleteness());
            assertEquals(report1.toolSuccessRatio(), report2.toolSuccessRatio());
            assertEquals(report1.taskSuccess(), report2.taskSuccess());
            assertEquals(report1.totalSteps(), report2.totalSteps());
            assertEquals(report1.qualityPassed(), report2.qualityPassed());
            assertNull(report1.evalOverallScore());
            assertNull(report1.evalDimensionScores());
        }
    }

    // ==================== 边界条件 ====================

    @Nested
    class 边界条件 {

        @Test
        void 单步骤Answer轨迹可通过() {
            var state = 正常完成状态()
                    .steps(List.of(new ReactStep.Answer("直接回答")))
                    .stepCount(1)
                    .build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness());
            assertEquals(1, report.totalSteps());
            assertTrue(report.qualityPassed());
        }

        @Test
        void 仅含Thought步骤且有finalOutput时通过() {
            var state = 正常完成状态()
                    .steps(List.of(new ReactStep.Thought("深度思考")))
                    .stepCount(1)
                    .finalOutput("思考结果")
                    .build();
            var report = assessor.assess(state);

            assertTrue(report.trajectoryCompleteness());
            assertTrue(report.qualityPassed());
        }

        @Test
        void 大量步骤时正确统计() {
            var steps = new java.util.ArrayList<ReactStep>();
            for (int i = 0; i < 50; i++) {
                steps.add(new ReactStep.ToolCall("t" + i, "工具" + i, "{}", 10));
                steps.add(new ReactStep.Observation("t" + i, "工具" + i, true, "成功", 5));
            }
            steps.add(new ReactStep.Answer("完成"));

            var state = 正常完成状态()
                    .steps(steps)
                    .stepCount(101)
                    .build();
            var report = assessor.assess(state);

            assertEquals(101, report.totalSteps());
            assertEquals(1.0f, report.toolSuccessRatio(), 0.001f);
            assertTrue(report.qualityPassed());
        }

        @Test
        void 自定义宽松系数为零时Eval高分不放宽() {
            properties.getExperience().setEvalQualityRelaxFactor(0f);
            assessor = new TrajectoryQualityAssessor(properties);

            // effectiveMinRatio = 0.3 * 0 = 0，任何成功率都 >= 0
            var state = 含工具调用状态(0, 5);
            var evalResult = 构建评估结果(0.9, Map.of());

            var report = assessor.assess(state, evalResult);

            // 0f >= 0f 为 true，所以成功率门控通过
            assertTrue(report.qualityPassed(), "宽松系数为 0 时 effectiveMinRatio=0，任何成功率都通过");
        }

        @Test
        void 自定义宽松系数为一时Eval高分不改变门控() {
            properties.getExperience().setEvalQualityRelaxFactor(1f);
            assessor = new TrajectoryQualityAssessor(properties);

            // effectiveMinRatio = 0.3 * 1 = 0.3，不放宽
            // 2 成功 + 8 失败 = 成功率 0.2 < 0.3 不通过
            var state = 含工具调用状态(2, 8);
            var evalResult = 构建评估结果(0.9, Map.of());

            var report = assessor.assess(state, evalResult);

            assertFalse(report.qualityPassed(), "宽松系数为 1 时相当于不放宽");
        }

        @Test
        void 混合成功失败的Observation步骤正确计算比例() {
            var steps = List.<ReactStep>of(
                    new ReactStep.ToolCall("t1", "工具1", "{}", 10),
                    new ReactStep.Observation("t1", "工具1", true, "成功", 5),
                    new ReactStep.Thought("中间思考"),
                    new ReactStep.ToolCall("t2", "工具2", "{}", 10),
                    new ReactStep.Observation("t2", "工具2", false, "失败", 5),
                    new ReactStep.ToolCall("t3", "工具3", "{}", 10),
                    new ReactStep.Observation("t3", "工具3", true, "成功", 5),
                    new ReactStep.Answer("完成")
            );
            var state = 正常完成状态()
                    .steps(steps)
                    .stepCount(8)
                    .build();
            var report = assessor.assess(state);

            // 3 个 Observation，2 个成功 -> 2/3 ≈ 0.6667
            assertEquals(2f / 3f, report.toolSuccessRatio(), 0.001f);
            assertTrue(report.qualityPassed(), "成功率 0.667 > 门控值 0.3 应通过");
        }

        @Test
        void ToolCall不计入Observation统计() {
            // 只有 ToolCall 没有 Observation 时，totalObservations=0
            var steps = List.<ReactStep>of(
                    new ReactStep.ToolCall("t1", "工具1", "{}", 10),
                    new ReactStep.Answer("完成")
            );
            var state = 正常完成状态()
                    .steps(steps)
                    .stepCount(2)
                    .build();
            var report = assessor.assess(state);

            assertEquals(0f, report.toolSuccessRatio(), "无 Observation 时成功率为 0");
            assertTrue(report.qualityPassed(), "totalObservations=0 时工具门控不生效");
        }
    }
}
