package com.lifepilot.eval.engine;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.trace.TraceRecorder;
import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.evaluator.TrajectoryEvaluator;
import com.lifepilot.eval.judge.JudgeResult;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评估引擎 — 协调场景加载、Agent 执行、轨迹采集、评估、报告。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>执行单个场景评估：Agent 执行 → 采集轨迹 → 规则评估 → 可选 LLM Judge → 异步持久化</li>
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

    // 预留：ScenarioLoader 供 JUnit 5 集成层使用
    @SuppressWarnings("unused")
    private final ScenarioLoader scenarioLoader;
    private final AgentLoop agentLoop;
    // 预留：TraceRecorder 供后续真实轨迹采集使用
    @SuppressWarnings("unused")
    private final TraceRecorder traceRecorder;
    private final TrajectoryEvaluator trajectoryEvaluator;
    private final LlmJudge llmJudge;
    private final EvalStore evalStore;
    private final EvalReport evalReport;
    private final EvalConfigProperties config;

    public EvalEngine(ScenarioLoader scenarioLoader,
                      AgentLoop agentLoop,
                      TraceRecorder traceRecorder,
                      TrajectoryEvaluator trajectoryEvaluator,
                      LlmJudge llmJudge,
                      EvalStore evalStore,
                      EvalReport evalReport,
                      EvalConfigProperties config) {
        this.scenarioLoader = scenarioLoader;
        this.agentLoop = agentLoop;
        this.traceRecorder = traceRecorder;
        this.trajectoryEvaluator = trajectoryEvaluator;
        this.llmJudge = llmJudge;
        this.evalStore = evalStore;
        this.evalReport = evalReport;
        this.config = config;
        log.info("EvalEngine 初始化完成");
    }

    /**
     * 执行单个场景评估。
     *
     * <p>流程：执行 Agent → 构建合成轨迹 → 规则评估 → 可选 LLM Judge → 异步持久化。</p>
     * <p>Agent 执行超时或异常时，该场景评分为 0.0，记录到 violations。</p>
     *
     * @param scenario Benchmark 场景
     * @return 评估结果
     */
    public EvalResult evaluateScenario(BenchmarkScenario scenario) {
        log.info("开始评估场景: scenarioId={}, name={}", scenario.id(), scenario.name());

        try {
            // 1. 构造 AgentRequest 并执行 Agent
            var request = new AgentRequest(scenario.userInput(), "eval-" + scenario.id(), "eval");
            AgentResponse response = agentLoop.run(request);

            // 2. 构建合成轨迹步骤（AgentLoop 内部管理轨迹，外部无法直接获取步骤）
            List<TraceStep> syntheticSteps = buildSyntheticSteps(response, scenario);

            // 3. 规则评估
            EvalResult evalResult = trajectoryEvaluator.evaluate(syntheticSteps, scenario);

            // 4. 可选 LLM Judge 语义评估
            evalResult = applyLlmJudge(evalResult, response, scenario);

            // 5. 填充元数据
            evalResult = enrichMetadata(evalResult);

            // 6. 异步持久化
            evalStore.persistAsync(evalResult);

            log.info("场景评估完成: scenarioId={}, overallScore={}", scenario.id(), evalResult.overallScore());
            return evalResult;

        } catch (Exception e) {
            log.error("场景评估异常: scenarioId={}, error={}", scenario.id(), e.getMessage(), e);
            return buildFailedResult(scenario, e);
        }
    }

    /**
     * 执行批量场景评估。
     *
     * <p>遍历场景列表逐个评估，收集所有结果后生成汇总报告。
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
            EvalResult result = evaluateScenario(scenario);
            // 统一设置 evalRunId
            result = result.toBuilder().evalRunId(evalRunId).build();
            results.add(result);
        }

        ReportSummary summary = evalReport.generateSummary(results, evalRunId);
        log.info("批量评估完成: evalRunId={}, 总场景={}, 通过={}, 失败={}",
                evalRunId, summary.totalScenarios(), summary.passCount(), summary.failCount());

        return summary;
    }

    /**
     * 从 AgentResponse 构建合成轨迹步骤。
     *
     * <p>AgentLoop 内部管理轨迹记录和持久化，外部无法直接获取 TraceStep 列表。
     * 因此根据 AgentResponse 的元数据构建合成步骤，供 TrajectoryEvaluator 评估。</p>
     *
     * @param response AgentResponse
     * @param scenario 场景定义
     * @return 合成轨迹步骤列表
     */
    private List<TraceStep> buildSyntheticSteps(AgentResponse response, BenchmarkScenario scenario) {
        List<TraceStep> steps = new ArrayList<>();
        int stepCount = Math.max(response.stepCount(), 1);
        int tokensPerStep = stepCount > 0 ? response.tokensUsed() / stepCount : 0;

        for (int i = 0; i < stepCount; i++) {
            var step = TraceStep.builder()
                    .traceId(response.traceId())
                    .stepIndex(i)
                    .phaseBefore(AgentPhase.UNDERSTANDING)
                    .phaseAfter(AgentPhase.EXECUTING)
                    .action(new Action.ResponseGenerated(response.content(), List.of()))
                    .toolId(null)
                    .toolInput(null)
                    .toolOutput(null)
                    .blocked(false)
                    .blockReason(null)
                    .tokensUsed(tokensPerStep)
                    .latencyMs(0)
                    .timestamp(Instant.now())
                    .build();
            steps.add(step);
        }

        return steps;
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
     * 填充评估结果的元数据（Git 信息、evalRunId）。
     *
     * @param evalResult 原始评估结果
     * @return 填充元数据后的评估结果
     */
    private EvalResult enrichMetadata(EvalResult evalResult) {
        String gitCommitHash = resolveGitCommitHash();
        String gitBranch = resolveGitBranch();

        return evalResult.toBuilder()
                .gitCommitHash(gitCommitHash)
                .gitBranch(gitBranch)
                .evalRunId(evalResult.evalRunId() != null
                        ? evalResult.evalRunId() : UUID.randomUUID().toString())
                .build();
    }

    /**
     * 构建失败场景的评估结果（评分 0.0，异常信息记录到 violations）。
     *
     * @param scenario 场景定义
     * @param e        异常
     * @return 失败评估结果
     */
    private EvalResult buildFailedResult(BenchmarkScenario scenario, Exception e) {
        String errorMessage = "Agent 执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();

        EvalResult failedResult = EvalResult.builder()
                .evalId(UUID.randomUUID().toString())
                .traceId("")
                .scenarioId(scenario.id())
                .dimensionScores(java.util.Map.of())
                .overallScore(0.0)
                .violations(List.of(errorMessage))
                .suggestions(List.of())
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .gitCommitHash(resolveGitCommitHash())
                .gitBranch(resolveGitBranch())
                .evalRunId(UUID.randomUUID().toString())
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
