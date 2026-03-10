package com.lifepilot.eval.engine;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.observability.evaluation.EvaluationConfig;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.EvaluationResult;
import com.lifepilot.observability.trace.TraceNotFoundException;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.judge.JudgeResult;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评估引擎 — 协调场景加载、Agent 执行、轨迹采集、评估、报告。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>执行单个场景评估：Agent 执行 → TraceQuery 获取真实轨迹 → EvaluationCore 评估 → 可选 LLM Judge → 异步持久化</li>
 *   <li>执行批量场景评估：遍历场景列表 → 逐个评估 → 生成汇总报告</li>
 * </ul>
 *
 * <p>Agent 执行超时或异常时，该场景评分为 0.0，记录到 violations，继续下一个场景。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class EvalEngine {

    private static final Logger log = LoggerFactory.getLogger(EvalEngine.class);

    private final ScenarioLoader scenarioLoader;
    private final AgentLoop agentLoop;
    private final TraceQuery traceQuery;
    private final EvaluationCore evaluationCore;
    private final LlmJudge llmJudge;
    private final EvalStore evalStore;
    private final EvalReport evalReport;
    private final DynamicToolRegistry toolRegistry;
    private final EvalConfigProperties config;

    public EvalEngine(ScenarioLoader scenarioLoader,
                      AgentLoop agentLoop,
                      TraceQuery traceQuery,
                      EvaluationCore evaluationCore,
                      LlmJudge llmJudge,
                      EvalStore evalStore,
                      EvalReport evalReport,
                      DynamicToolRegistry toolRegistry,
                      EvalConfigProperties config) {
        this.scenarioLoader = scenarioLoader;
        this.agentLoop = agentLoop;
        this.traceQuery = traceQuery;
        this.evaluationCore = evaluationCore;
        this.llmJudge = llmJudge;
        this.evalStore = evalStore;
        this.evalReport = evalReport;
        this.toolRegistry = toolRegistry;
        this.config = config;
        log.info("EvalEngine 初始化完成");
    }

    /**
     * 执行单个场景评估。
     *
     * <p>流程：执行 Agent → TraceQuery 获取真实轨迹 → EvaluationCore 评估 → 可选 LLM Judge → 异步持久化。</p>
     * <p>Agent 执行超时或异常时，该场景评分为 0.0，记录到 violations。</p>
     *
     * @param scenario  Benchmark 场景
     * @param evalRunId 评估运行 ID（批量模式传入统一 ID）
     * @return 评估结果
     */
    public EvalResult evaluateScenario(BenchmarkScenario scenario, String evalRunId) {
        log.info("开始评估场景: scenarioId={}, evalRunId={}", scenario.id(), evalRunId);
        var startTime = Instant.now();

        try {
            // 1. 构造 AgentRequest 并执行 Agent
            var request = new AgentRequest(scenario.userInput(), "eval-" + scenario.id(), "eval");
            AgentResponse response = agentLoop.run(request);

            // 2. 通过 TraceQuery 获取真实轨迹步骤
            String traceId = response.traceId();
            List<TraceStep> steps;
            if (traceId == null || traceId.isBlank()) {
                log.warn("AgentResponse traceId 为空，评分降级: scenarioId={}", scenario.id());
                return buildFailedResult(scenario, evalRunId, "traceId 为空，无法获取轨迹步骤");
            }

            try {
                steps = traceQuery.getSteps(traceId);
            } catch (TraceNotFoundException e) {
                log.warn("TraceQuery 获取步骤失败: scenarioId={}, traceId={}, error={}",
                        scenario.id(), traceId, e.getMessage());
                return buildFailedResult(scenario, evalRunId, "轨迹步骤获取失败: " + e.getMessage());
            }

            // 3. EvaluationCore 五维评估
            EvaluationConfig evalConfig = buildEvaluationConfig(scenario);
            EvaluationResult coreResult = evaluationCore.evaluate(steps, evalConfig, traceId);

            // 4. 构建 EvalResult
            EvalResult evalResult = EvalResult.builder()
                    .evalId(UUID.randomUUID().toString())
                    .traceId(traceId)
                    .scenarioId(scenario.id())
                    .dimensionScores(Map.of(
                            "toolSelection", coreResult.toolSelectionScore(),
                            "parameterValidity", coreResult.parameterValidityScore(),
                            "stepEfficiency", coreResult.stepEfficiencyScore(),
                            "policyCompliance", coreResult.policyComplianceScore(),
                            "tokenEfficiency", coreResult.tokenEfficiencyScore()
                    ))
                    .overallScore(coreResult.overallScore())
                    .violations(coreResult.violations())
                    .suggestions(coreResult.suggestions())
                    .llmJudgeScore(null)
                    .llmJudgeJustification(null)
                    .llmJudgeTokensUsed(0)
                    .evaluatedAt(Instant.now())
                    .gitCommitHash(resolveGitCommitHash())
                    .gitBranch(resolveGitBranch())
                    .evalRunId(evalRunId)
                    .build();

            // 5. 可选 LLM Judge 语义评估
            evalResult = applyLlmJudge(evalResult, response, scenario);

            // 6. 异步持久化
            evalStore.persistAsync(evalResult);

            long elapsed = java.time.Duration.between(startTime, Instant.now()).toMillis();
            log.info("场景评估完成: scenarioId={}, evalRunId={}, overallScore={}, 耗时={}ms",
                    scenario.id(), evalRunId, evalResult.overallScore(), elapsed);
            return evalResult;

        } catch (Exception e) {
            log.error("场景评估异常: scenarioId={}, error={}", scenario.id(), e.getMessage(), e);
            return buildFailedResult(scenario, evalRunId, "Agent 执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 执行批量场景评估。
     *
     * <p>evalRunId 在循环前生成，作为参数传入 evaluateScenario，确保批次内所有结果共享同一 ID。
     * 单个场景失败不影响其他场景的评估。</p>
     *
     * @param scenarios 场景列表
     * @return 报告汇总
     */
    public ReportSummary evaluateBatch(List<BenchmarkScenario> scenarios) {
        String evalRunId = UUID.randomUUID().toString();
        log.info("开始批量评估: evalRunId={}, 场景数={}", evalRunId, scenarios.size());

        List<EvalResult> results = new ArrayList<>();
        for (BenchmarkScenario scenario : scenarios) {
            try {
                EvalResult result = evaluateScenario(scenario, evalRunId);
                results.add(result);
            } catch (Exception e) {
                log.error("批量评估中场景异常: scenarioId={}, evalRunId={}, error={}",
                        scenario.id(), evalRunId, e.getMessage(), e);
                results.add(buildFailedResult(scenario, evalRunId,
                        "批量评估异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
            }
        }

        ReportSummary summary = evalReport.generateSummary(results, evalRunId);
        log.info("批量评估完成: evalRunId={}, 总场景={}, 通过={}, 失败={}",
                evalRunId, summary.totalScenarios(), summary.passCount(), summary.failCount());

        return summary;
    }

    /**
     * 从 BenchmarkScenario 构建 EvaluationConfig。
     *
     * @param scenario 场景定义
     * @return 评估配置
     */
    private EvaluationConfig buildEvaluationConfig(BenchmarkScenario scenario) {
        Map<String, Double> weights = scenario.dimensionWeights();
        return new EvaluationConfig(
                weights.getOrDefault("toolSelection", 0.2),
                weights.getOrDefault("parameterValidity", 0.2),
                weights.getOrDefault("stepEfficiency", 0.2),
                weights.getOrDefault("policyCompliance", 0.2),
                weights.getOrDefault("tokenEfficiency", 0.2),
                scenario.expectedStepCount(),
                scenario.expectedTokenBudget(),
                scenario.expectedToolCalls()
        );
    }

    /**
     * 如果场景定义了 LLM Judge 标准，执行 LLM 语义评估并合并到结果中。
     *
     * @param evalResult 当前评估结果
     * @param response   Agent 响应
     * @param scenario   场景定义
     * @return 合并 LLM Judge 结果后的评估结果
     */
    private EvalResult applyLlmJudge(EvalResult evalResult, AgentResponse response,
                                      BenchmarkScenario scenario) {
        String criteria = scenario.llmJudgeCriteria();
        if (criteria == null || criteria.isBlank()) {
            return evalResult;
        }

        try {
            String expectedPattern = scenario.expectedOutputPattern() != null
                    ? scenario.expectedOutputPattern() : "";
            JudgeResult judgeResult = llmJudge.judge(
                    response.content(), expectedPattern, criteria);

            return evalResult.toBuilder()
                    .llmJudgeScore(judgeResult.score())
                    .llmJudgeJustification(judgeResult.justification())
                    .llmJudgeTokensUsed(judgeResult.tokensUsed())
                    .build();
        } catch (Exception e) {
            log.warn("LLM Judge 执行失败: scenarioId={}, error={}", scenario.id(), e.getMessage());
            double fallbackScore = config.getLlmJudge().getFallbackScore();
            return evalResult.toBuilder()
                    .llmJudgeScore(fallbackScore)
                    .llmJudgeJustification("LLM Judge 执行失败: " + e.getMessage())
                    .llmJudgeTokensUsed(0)
                    .build();
        }
    }

    /**
     * 构建失败场景的评估结果（评分 0.0，错误信息记录到 violations）。
     *
     * @param scenario  场景定义
     * @param evalRunId 评估运行 ID
     * @param reason    失败原因
     * @return 失败评估结果
     */
    private EvalResult buildFailedResult(BenchmarkScenario scenario, String evalRunId, String reason) {
        EvalResult failedResult = EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("")
                .scenarioId(scenario.id())
                .dimensionScores(Map.of())
                .overallScore(0.0)
                .violations(List.of(reason))
                .suggestions(List.of())
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .gitCommitHash(resolveGitCommitHash())
                .gitBranch(resolveGitBranch())
                .evalRunId(evalRunId)
                .build();

        // 异步持久化失败结果
        evalStore.persistAsync(failedResult);
        return failedResult;
    }

    /**
     * 解析当前 Git commit hash。
     *
     * @return commit hash，获取失败时返回 null
     */
    private String resolveGitCommitHash() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && !output.isBlank() ? output : null;
        } catch (Exception e) {
            log.debug("获取 Git commit hash 失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析当前 Git 分支名。
     *
     * @return 分支名，获取失败时返回 null
     */
    private String resolveGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && !output.isBlank() ? output : null;
        } catch (Exception e) {
            log.debug("获取 Git 分支名失败: error={}", e.getMessage());
            return null;
        }
    }
}
