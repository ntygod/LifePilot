package com.lifepilot.eval.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.evaluator.*;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EvalEngine + AgentLoop 端到端集成测试。
 *
 * <p>轻量级测试，不加载 Spring 上下文。手动装配 EvalEngine 及其依赖，
 * Mock AgentLoop 和 LlmRouter，使用内存 SQLite 验证完整评估流程：
 * 场景加载 → Agent 执行 → 轨迹评估 → 持久化 → 报告生成。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class EvalEngine_AgentLoop_集成测试 {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS eval_results (
                eval_id                 TEXT PRIMARY KEY,
                trace_id                TEXT,
                scenario_id             TEXT NOT NULL,
                dimension_scores_json   TEXT NOT NULL,
                overall_score           REAL NOT NULL,
                violations_json         TEXT NOT NULL,
                suggestions_json        TEXT NOT NULL,
                llm_judge_score         REAL,
                llm_judge_justification TEXT,
                llm_judge_tokens_used   INTEGER DEFAULT 0,
                git_commit_hash         TEXT,
                git_branch              TEXT,
                eval_run_id             TEXT NOT NULL,
                evaluated_at            TEXT NOT NULL,
                created_at              TEXT NOT NULL
            )
            """;

    // ---- 基础设施 ----
    private SingleConnectionDataSource dataSource;
    private EvalStore evalStore;
    private EvalEngine evalEngine;
    private EvalReport evalReport;
    private ScenarioLoader scenarioLoader;
    private Path tempScenarioDir;

    // ---- Mock 依赖 ----
    private AgentLoop agentLoop;
    private LlmRouter llmRouter;
    private TraceRecorder traceRecorder;
    private DynamicToolRegistry toolRegistry;

    @BeforeEach
    void setUp() throws IOException {
        // 1. 内存 SQLite
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(CREATE_TABLE);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_scenario_id ON eval_results(scenario_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_eval_run_id ON eval_results(eval_run_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_eval_results_evaluated_at ON eval_results(evaluated_at)");

        var objectMapper = new ObjectMapper();

        // 2. Mock 外部依赖
        agentLoop = mock(AgentLoop.class);
        llmRouter = mock(LlmRouter.class);
        traceRecorder = mock(TraceRecorder.class);
        toolRegistry = mock(DynamicToolRegistry.class);

        // DynamicToolRegistry.resolve() 默认返回空（合成步骤无 toolId）
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());

        // 3. 配置
        var config = new EvalConfigProperties();
        config.setDefaultPassThreshold(0.7);
        config.setDegradationThreshold(0.1);

        // 4. 创建临时场景目录并写入 YAML 场景文件
        tempScenarioDir = Files.createTempDirectory("eval-scenarios-");
        config.setScenarioDirectory(tempScenarioDir.toString());
        writeScenarioYaml("weather-query-001", "查询天气", "帮我查一下明天的天气",
                List.of("weather.query"), ".*天气.*", 1000, 3,
                "回答应包含温度和天气状况信息");
        writeScenarioYaml("todo-create-002", "创建待办", "帮我创建一个明天开会的待办",
                List.of("todo.create"), ".*待办.*", 800, 2, null);

        // 5. 装配真实组件
        scenarioLoader = new ScenarioLoader(config);
        evalStore = new EvalStore(jdbcTemplate, objectMapper);

        List<DimensionEvaluator> evaluators = List.of(
                new ToolSelectionEvaluator(),
                new ParameterValidityEvaluator(toolRegistry),
                new StepEfficiencyEvaluator(),
                new PolicyComplianceEvaluator(),
                new TokenEfficiencyEvaluator()
        );
        var trajectoryEvaluator = new TrajectoryEvaluator(evaluators, toolRegistry);
        var llmJudge = new LlmJudge(llmRouter, config);
        evalReport = new EvalReport(evalStore, config);

        evalEngine = new EvalEngine(
                scenarioLoader, agentLoop, traceRecorder,
                trajectoryEvaluator, llmJudge, evalStore, evalReport, config
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        dataSource.destroy();
        // 清理临时场景目录
        if (tempScenarioDir != null && Files.exists(tempScenarioDir)) {
            try (var walk = Files.walk(tempScenarioDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    // ================================================================
    //  测试用例
    // ================================================================

    @Test
    void 单场景评估_完整流程_场景加载到持久化() throws InterruptedException {
        // 准备 Mock：AgentLoop 返回受控响应
        var agentResponse = new AgentResponse(
                "trace-weather-001", "eval-weather-query-001",
                "明天天气晴，温度 25°C", 500, 3, null);
        when(agentLoop.run(any())).thenReturn(agentResponse);

        // Mock LlmRouter 用于 LlmJudge（场景有 llmJudgeCriteria）
        var llmJudgeResponse = new LlmResponse(
                "{\"score\": 0.85, \"justification\": \"回答包含温度和天气状况\"}",
                50, 30, "test-provider", "test-model", 100, false);
        when(llmRouter.call(anyString(), anyString(), any())).thenReturn(llmJudgeResponse);

        // 加载场景
        var scenarios = scenarioLoader.loadAll();
        var weatherScenario = scenarios.stream()
                .filter(s -> s.id().equals("weather-query-001"))
                .findFirst().orElseThrow();

        // 执行评估
        EvalResult result = evalEngine.evaluateScenario(weatherScenario);

        // 验证评估结果
        assertThat(result).isNotNull();
        assertThat(result.scenarioId()).isEqualTo("weather-query-001");
        assertThat(result.overallScore()).isGreaterThan(0.0);
        assertThat(result.dimensionScores()).isNotEmpty();
        assertThat(result.evalId()).isNotBlank();
        assertThat(result.traceId()).isEqualTo("trace-weather-001");

        // 验证 LLM Judge 被调用（场景有 llmJudgeCriteria）
        assertThat(result.llmJudgeScore()).isNotNull();
        assertThat(result.llmJudgeScore()).isBetween(0.0, 1.0);

        // 等待异步持久化完成
        Thread.sleep(200);

        // 验证持久化到 SQLite
        var persisted = evalStore.findByScenarioId("weather-query-001", 10);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.getFirst().scenarioId()).isEqualTo("weather-query-001");
        assertThat(persisted.getFirst().overallScore()).isEqualTo(result.overallScore());

        // 验证 AgentLoop 被调用
        verify(agentLoop).run(any());
    }

    @Test
    void 单场景评估_无LlmJudge标准_跳过语义评估() throws InterruptedException {
        // 准备 Mock
        var agentResponse = new AgentResponse(
                "trace-todo-002", "eval-todo-create-002",
                "已创建待办：明天开会", 400, 2, null);
        when(agentLoop.run(any())).thenReturn(agentResponse);

        // 加载无 llmJudgeCriteria 的场景
        var scenarios = scenarioLoader.loadAll();
        var todoScenario = scenarios.stream()
                .filter(s -> s.id().equals("todo-create-002"))
                .findFirst().orElseThrow();

        // 执行评估
        EvalResult result = evalEngine.evaluateScenario(todoScenario);

        // 验证 LLM Judge 未被调用
        assertThat(result.llmJudgeScore()).isNull();
        verifyNoInteractions(llmRouter);

        // 验证评估结果有效
        assertThat(result.overallScore()).isGreaterThan(0.0);
        assertThat(result.scenarioId()).isEqualTo("todo-create-002");

        // 等待异步持久化
        Thread.sleep(200);

        // 验证持久化
        var persisted = evalStore.findByScenarioId("todo-create-002", 10);
        assertThat(persisted).hasSize(1);
    }

    @Test
    void 批量评估_多场景_生成汇总报告() throws InterruptedException {
        // 准备 Mock：两个场景返回不同响应
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, com.lifepilot.agent.model.AgentRequest.class);
            if (request.sessionId().contains("weather")) {
                return new AgentResponse(
                        "trace-batch-weather", request.sessionId(),
                        "明天天气晴，温度 25°C", 500, 3, null);
            } else {
                return new AgentResponse(
                        "trace-batch-todo", request.sessionId(),
                        "已创建待办：明天开会", 400, 2, null);
            }
        });

        // Mock LlmRouter 用于 LlmJudge
        var llmJudgeResponse = new LlmResponse(
                "{\"score\": 0.90, \"justification\": \"回答质量良好\"}",
                50, 30, "test-provider", "test-model", 100, false);
        when(llmRouter.call(anyString(), anyString(), any())).thenReturn(llmJudgeResponse);

        // 加载所有场景
        var scenarios = scenarioLoader.loadAll();
        assertThat(scenarios).hasSize(2);

        // 执行批量评估
        ReportSummary summary = evalEngine.evaluateBatch(scenarios);

        // 验证报告汇总
        assertThat(summary).isNotNull();
        assertThat(summary.totalScenarios()).isEqualTo(2);
        assertThat(summary.passCount() + summary.failCount()).isEqualTo(2);
        assertThat(summary.averageOverallScore()).isGreaterThan(0.0);
        assertThat(summary.evalRunId()).isNotBlank();
        assertThat(summary.dimensionAverages()).isNotEmpty();

        // 等待异步持久化
        Thread.sleep(300);

        // 验证 AgentLoop 被调用两次
        verify(agentLoop, times(2)).run(any());
    }

    @Test
    void 报告生成_控制台输出和JSON导出() throws InterruptedException {
        // 准备 Mock
        var agentResponse = new AgentResponse(
                "trace-report-test", "eval-weather-query-001",
                "明天天气晴", 500, 3, null);
        when(agentLoop.run(any())).thenReturn(agentResponse);
        when(llmRouter.call(anyString(), anyString(), any())).thenReturn(
                new LlmResponse("{\"score\": 0.80, \"justification\": \"良好\"}",
                        50, 30, "test", "test", 100, false));

        var scenarios = scenarioLoader.loadAll();
        ReportSummary summary = evalEngine.evaluateBatch(scenarios);

        // 验证 JSON 导出
        String json = evalReport.exportJson(summary);
        assertThat(json).isNotBlank();
        assertThat(json).contains("evalRunId");
        assertThat(json).contains("totalScenarios");
        assertThat(json).contains("averageOverallScore");

        // 验证控制台输出不抛异常
        evalReport.printToConsole(summary);
    }

    @Test
    void Agent执行异常_场景评分为零_不阻断后续场景() throws InterruptedException {
        // 第一个场景抛异常，第二个正常
        when(agentLoop.run(any()))
                .thenThrow(new RuntimeException("Agent 执行超时"))
                .thenReturn(new AgentResponse(
                        "trace-ok", "eval-todo-create-002",
                        "已创建待办", 400, 2, null));

        var scenarios = scenarioLoader.loadAll();
        ReportSummary summary = evalEngine.evaluateBatch(scenarios);

        // 验证批量评估完成（异常场景不阻断）
        assertThat(summary.totalScenarios()).isEqualTo(2);
        // 至少有一个失败（异常场景评分 0.0）
        assertThat(summary.failCount()).isGreaterThanOrEqualTo(1);

        // 等待异步持久化
        Thread.sleep(300);

        // 验证 AgentLoop 被调用两次（异常后继续）
        verify(agentLoop, times(2)).run(any());
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    /**
     * 写入 YAML 场景文件到临时目录。
     */
    private void writeScenarioYaml(String id, String name, String userInput,
                                    List<String> expectedToolCalls,
                                    String expectedOutputPattern,
                                    int expectedTokenBudget, int expectedStepCount,
                                    String llmJudgeCriteria) throws IOException {
        var sb = new StringBuilder();
        sb.append("id: \"").append(id).append("\"\n");
        sb.append("name: \"").append(name).append("\"\n");
        sb.append("userInput: \"").append(userInput).append("\"\n");
        sb.append("expectedToolCalls:\n");
        for (String tool : expectedToolCalls) {
            sb.append("  - \"").append(tool).append("\"\n");
        }
        if (expectedOutputPattern != null) {
            sb.append("expectedOutputPattern: \"").append(expectedOutputPattern).append("\"\n");
        }
        sb.append("dimensionWeights:\n");
        sb.append("  toolSelection: 0.30\n");
        sb.append("  parameterValidity: 0.20\n");
        sb.append("  stepEfficiency: 0.20\n");
        sb.append("  policyCompliance: 0.20\n");
        sb.append("  tokenEfficiency: 0.10\n");
        sb.append("timeoutSeconds: 60\n");
        sb.append("expectedTokenBudget: ").append(expectedTokenBudget).append("\n");
        sb.append("expectedStepCount: ").append(expectedStepCount).append("\n");
        sb.append("tags:\n");
        sb.append("  - \"integration-test\"\n");
        if (llmJudgeCriteria != null) {
            sb.append("llmJudgeCriteria: \"").append(llmJudgeCriteria).append("\"\n");
        }

        var fileName = id + ".yml";
        Files.writeString(tempScenarioDir.resolve(fileName), sb.toString());
    }
}
