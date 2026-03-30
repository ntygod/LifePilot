package com.lifepilot.eval.engine;

import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.observability.evaluation.EvaluationConfig;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.EvaluationResult;
import com.lifepilot.observability.trace.TraceNotFoundException;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.eval.evaluator.DiagnosticEnricher;
import com.lifepilot.eval.model.DiagnosticReport;
import com.lifepilot.memory.experience.ExperienceSummarizer;
import com.lifepilot.eval.judge.JudgeResult;
import com.lifepilot.eval.judge.LlmJudge;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.report.EvalReport;
import com.lifepilot.eval.report.ReportSummary;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.eval.scenario.MockToolSpec;
import com.lifepilot.eval.scenario.ScenarioLoader;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 评估引擎 — 协调场景加载、Agent 执行、轨迹采集、评估、报告。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>执行单个场景评估：Agent 执行 → TraceQuery 获取真实轨迹 → EvaluationCore 评估 → 可选 LLM Judge → 异步持久化</li>
 *   <li>执行批量场景评估：并行评估场景列表 → 生成汇总报告</li>
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
    private final AgentOrchestrator agentOrchestrator;
    private final TraceQuery traceQuery;
    private final EvaluationCore evaluationCore;
    private final LlmJudge llmJudge;
    private final EvalStore evalStore;
    private final EvalReport evalReport;
    private final DynamicToolRegistry toolRegistry;
    private final EvalConfigProperties config;
    private final ExecutorService evalExecutor;
    private final DiagnosticEnricher diagnosticEnricher;
    private final ObjectMapper objectMapper;
    @Nullable
    private final ExperienceSummarizer experienceSummarizer;

    /** 缓存 Git 信息，整个 bean 生命周期只解析一次。 */
    private volatile GitInfo cachedGitInfo;

    private record GitInfo(@Nullable String commitHash, @Nullable String branch) {}

    private record MockToolRegistration(String toolId, @Nullable ToolContract previousTool) {}

    public EvalEngine(ScenarioLoader scenarioLoader,
                      AgentOrchestrator agentOrchestrator,
                      TraceQuery traceQuery,
                      EvaluationCore evaluationCore,
                      LlmJudge llmJudge,
                      EvalStore evalStore,
                      EvalReport evalReport,
                      DynamicToolRegistry toolRegistry,
                      EvalConfigProperties config,
                      ExecutorService evalExecutor,
                      DiagnosticEnricher diagnosticEnricher,
                      ObjectMapper objectMapper,
                      @Nullable ExperienceSummarizer experienceSummarizer) {
        this.scenarioLoader = scenarioLoader;
        this.agentOrchestrator = agentOrchestrator;
        this.traceQuery = traceQuery;
        this.evaluationCore = evaluationCore;
        this.llmJudge = llmJudge;
        this.evalStore = evalStore;
        this.evalReport = evalReport;
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.evalExecutor = evalExecutor;
        this.diagnosticEnricher = diagnosticEnricher;
        this.objectMapper = objectMapper;
        this.experienceSummarizer = experienceSummarizer;
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
            // 0. 注册 Mock 工具（如果场景定义了 mockToolResponses）
            List<MockToolRegistration> mockToolIds = registerMockTools(scenario);

            try {
                // 1. 构造 AgentRequest 并执行 Agent（带超时控制）
                String systemPrompt = buildSystemPrompt(scenario);
                var request = new AgentRequest(scenario.userInput(), "eval-" + scenario.id(), InteractionSource.system("eval"),
                        null,
                        systemPrompt, null, null, 0, null, null, null, null);

                int timeout = scenario.timeoutSeconds() > 0
                        ? scenario.timeoutSeconds()
                        : config.getExecution().getDefaultTimeoutSeconds();

                AgentResponse response;
                try {
                    response = CompletableFuture.supplyAsync(
                            () -> agentOrchestrator.run(request),
                            evalExecutor
                    ).orTimeout(timeout, TimeUnit.SECONDS).join();
                } catch (java.util.concurrent.CompletionException ce) {
                    if (ce.getCause() instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Agent 执行超时: scenarioId={}, timeout={}s", scenario.id(), timeout);
                        return buildFailedResult(scenario, evalRunId,
                                "Agent 执行超时: 超过 %d 秒限制".formatted(timeout));
                    }
                    throw ce.getCause() instanceof Exception ex ? ex : ce;
                }

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
                var gitInfo = getGitInfo();
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
                        .gitCommitHash(gitInfo.commitHash())
                        .gitBranch(gitInfo.branch())
                        .evalRunId(evalRunId)
                        .build();

                // 5. 可选 LLM Judge 语义评估
                JudgeResult judgeResult = null;
                String criteria = scenario.llmJudgeCriteria();
                if (criteria != null && !criteria.isBlank()) {
                    try {
                        String expectedPattern = scenario.expectedOutputPattern() != null
                                ? scenario.expectedOutputPattern() : "";
                        judgeResult = llmJudge.judge(response.content(), expectedPattern, criteria);
                        evalResult = evalResult.toBuilder()
                                .llmJudgeScore(judgeResult.score())
                                .llmJudgeJustification(judgeResult.justification())
                                .llmJudgeTokensUsed(judgeResult.tokensUsed())
                                .build();
                    } catch (Exception e) {
                        log.warn("LLM Judge 执行失败: scenarioId={}, error={}", scenario.id(), e.getMessage());
                        double fallbackScore = config.getLlmJudge().getFallbackScore();
                        evalResult = evalResult.toBuilder()
                                .llmJudgeScore(fallbackScore)
                                .llmJudgeJustification("LLM Judge 执行失败: " + e.getMessage())
                                .llmJudgeTokensUsed(0)
                                .build();
                    }
                }

                // 6. 生成诊断报告
                try {
                    DiagnosticReport diagnostic = diagnosticEnricher.enrich(coreResult, judgeResult, scenario);
                    String diagnosticJson = objectMapper.writeValueAsString(diagnostic);
                    // 合并诊断建议到 suggestions
                    var enrichedSuggestions = new ArrayList<>(evalResult.suggestions());
                    enrichedSuggestions.addAll(diagnostic.actionableSuggestions());
                    evalResult = evalResult.toBuilder()
                            .diagnosticJson(diagnosticJson)
                            .suggestions(enrichedSuggestions)
                            .build();
                } catch (Exception e) {
                    log.warn("诊断报告生成失败: scenarioId={}, error={}", scenario.id(), e.getMessage());
                }

                // 7. 异步持久化
                evalStore.persistAsync(evalResult);

                long elapsed = Duration.between(startTime, Instant.now()).toMillis();
                log.info("场景评估完成: scenarioId={}, evalRunId={}, overallScore={}, 耗时={}ms",
                        scenario.id(), evalRunId, evalResult.overallScore(), elapsed);
                return evalResult;

            } finally {
                // 注销 Mock 工具
                unregisterMockTools(mockToolIds);
            }

        } catch (Exception e) {
            log.error("场景评估异常: scenarioId={}, error={}", scenario.id(), e.getMessage(), e);
            return buildFailedResult(scenario, evalRunId, "Agent 执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 安全执行单个场景评估（异常不外抛）。
     */
    private EvalResult evaluateScenarioSafe(BenchmarkScenario scenario, String evalRunId) {
        try {
            return evaluateScenario(scenario, evalRunId);
        } catch (Exception e) {
            log.error("批量评估中场景异常: scenarioId={}, evalRunId={}, error={}",
                    scenario.id(), evalRunId, e.getMessage(), e);
            return buildFailedResult(scenario, evalRunId,
                    "批量评估异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 执行批量场景评估。
     *
     * <p>evalRunId 在循环前生成，作为参数传入 evaluateScenario，确保批次内所有结果共享同一 ID。
     * 当并行度 > 1 时使用 Semaphore 控制并发，Mock 工具 ID 加命名空间前缀避免冲突。
     * 单个场景失败不影响其他场景的评估。</p>
     *
     * @param scenarios 场景列表
     * @return 报告汇总
     */
    public ReportSummary evaluateBatch(List<BenchmarkScenario> scenarios) {
        String evalRunId = UUID.randomUUID().toString();
        log.info("开始批量评估: evalRunId={}, 场景数={}", evalRunId, scenarios.size());

        int parallelism = config.getExecution().getParallelism();
        List<EvalResult> results;

        if (parallelism <= 1 || scenarios.size() <= 1) {
            // 串行执行
            results = scenarios.stream()
                    .map(s -> evaluateScenarioSafe(s, evalRunId))
                    .toList();
        } else {
            // 并行执行，用 Semaphore 控制并发度
            var semaphore = new Semaphore(parallelism);
            var futures = scenarios.stream()
                    .map(s -> CompletableFuture.supplyAsync(() -> {
                        semaphore.acquireUninterruptibly();
                        try {
                            return evaluateScenarioSafe(s, evalRunId);
                        } finally {
                            semaphore.release();
                        }
                    }, evalExecutor))
                    .toList();
            results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }

        ReportSummary summary = evalReport.generateSummary(results, evalRunId);
        log.info("批量评估完成: evalRunId={}, 总场景={}, 通过={}, 失败={}",
                evalRunId, summary.totalScenarios(), summary.passCount(), summary.failCount());

        // 触发经验提炼
        if (experienceSummarizer != null) {
            try {
                experienceSummarizer.summarizeFromEval(results, scenarios);
            } catch (Exception e) {
                log.warn("批量评估后经验提炼失败: evalRunId={}, error={}",
                        evalRunId, e.getMessage());
            }
        }

        return summary;
    }

    /**
     * 获取缓存的 Git 信息（懒加载，整个 bean 生命周期只解析一次）。
     */
    private GitInfo getGitInfo() {
        if (cachedGitInfo == null) {
            cachedGitInfo = new GitInfo(resolveGitCommitHash(), resolveGitBranch());
        }
        return cachedGitInfo;
    }

    /**
     * 从 BenchmarkScenario 的 initialContext 构建 systemPrompt。
     *
     * @param scenario 场景定义
     * @return systemPrompt 字符串，无 initialContext 时返回 null
     */
    private String buildSystemPrompt(BenchmarkScenario scenario) {
        var ctx = scenario.initialContext();
        if (ctx == null || ctx.isEmpty()) {
            return null;
        }
        return ctx.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(java.util.stream.Collectors.joining("\n", "初始上下文:\n", ""));
    }

    /**
     * 注册 Mock 工具到 DynamicToolRegistry。
     *
     * <p>优先使用 {@code mockTools}（智能 Mock），回退到 {@code mockToolResponses}（静态 Mock）。
     * Mock 工具 ID 加命名空间前缀 {@code eval-mock-{scenarioId}-{toolId}} 避免并行冲突。</p>
     *
     * @param scenario 场景定义
     * @return 已注册的 Mock 工具 ID 列表（用于后续注销）
     */
    private List<MockToolRegistration> registerMockTools(BenchmarkScenario scenario) {
        List<MockToolRegistration> registeredIds = new ArrayList<>();

        // 优先使用智能 Mock（MockToolSpec）
        var mockTools = scenario.mockTools();
        if (mockTools != null && !mockTools.isEmpty()) {
            for (MockToolSpec spec : mockTools) {
                String targetToolId = spec.toolId();
                ToolContract previousTool = toolRegistry.resolve(targetToolId).orElse(null);
                if (previousTool != null) {
                    toolRegistry.unregisterTool(targetToolId);
                }
                try {
                    var mockTool = BuiltinTool.builder()
                            .id(targetToolId)
                            .name("mock-" + spec.toolId())
                            .description("Mock 工具: " + spec.toolId())
                            .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                            .executor(input -> executeMockBehavior(spec, input))
                            .build();
                    toolRegistry.registerBuiltinTool(mockTool);
                    registeredIds.add(new MockToolRegistration(targetToolId, previousTool));
                    log.debug("智能 Mock 工具注册成功: toolId={}, 覆盖原工具={}",
                            targetToolId, previousTool != null);
                } catch (Exception e) {
                    if (previousTool != null) {
                        restoreTool(previousTool);
                    }
                    log.warn("智能 Mock 工具注册失败: toolId={}, error={}", targetToolId, e.getMessage());
                }
            }
            if (!registeredIds.isEmpty()) {
                log.info("智能 Mock 工具注册完成: scenarioId={}, count={}", scenario.id(), registeredIds.size());
            }
            return registeredIds;
        }

        // 回退到静态 Mock（mockToolResponses）
        var mockResponses = scenario.mockToolResponses();
        if (mockResponses == null || mockResponses.isEmpty()) {
            return List.of();
        }

        for (var entry : mockResponses.entrySet()) {
            String toolId = entry.getKey();
            String responseJson = entry.getValue();
            ToolContract previousTool = toolRegistry.resolve(toolId).orElse(null);
            if (previousTool != null) {
                toolRegistry.unregisterTool(toolId);
            }
            try {
                var mockTool = BuiltinTool.builder()
                        .id(toolId)
                        .name("mock-" + toolId)
                        .description("Mock 工具: " + toolId)
                        .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                        .executor(input -> ToolResult.success(Map.of("response", responseJson)))
                        .build();
                toolRegistry.registerBuiltinTool(mockTool);
                registeredIds.add(new MockToolRegistration(toolId, previousTool));
                log.debug("静态 Mock 工具注册成功: toolId={}, 覆盖原工具={}", toolId, previousTool != null);
            } catch (Exception e) {
                if (previousTool != null) {
                    restoreTool(previousTool);
                }
                log.warn("Mock 工具注册失败，跳过: toolId={}, error={}", toolId, e.getMessage());
            }
        }

        if (!registeredIds.isEmpty()) {
            log.info("Mock 工具注册完成: scenarioId={}, count={}", scenario.id(), registeredIds.size());
        }
        return registeredIds;
    }

    /**
     * 执行智能 Mock 行为匹配。
     *
     * <p>按 behaviors 顺序匹配参数，首个命中生效。无匹配时返回 defaultResponse。</p>
     */
    private ToolResult executeMockBehavior(MockToolSpec spec, ToolInput input) {
        String inputStr = input.parameters().toString();

        for (var behavior : spec.behaviors()) {
            // 参数匹配
            if (behavior.parameterPattern() != null) {
                if (!inputStr.matches(".*" + behavior.parameterPattern() + ".*")) {
                    continue;
                }
            }

            // 模拟延迟
            if (behavior.delayMs() > 0) {
                try {
                    Thread.sleep(behavior.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 模拟错误
            if (behavior.simulateError()) {
                return ToolResult.error("Mock 模拟错误: " + behavior.response());
            }

            return ToolResult.success(Map.of("response", behavior.response()));
        }

        // 无匹配，返回默认响应
        return ToolResult.success(Map.of("response", spec.defaultResponse()));
    }

    /**
     * 注销 Mock 工具。
     *
     * @param mockToolIds 需要注销的工具 ID 列表
     */
    private void unregisterMockTools(List<MockToolRegistration> mockToolIds) {
        for (MockToolRegistration registration : mockToolIds) {
            try {
                toolRegistry.unregisterTool(registration.toolId());
                if (registration.previousTool() != null) {
                    restoreTool(registration.previousTool());
                }
            } catch (Exception e) {
                log.warn("Mock 工具注销失败: toolId={}, error={}", registration.toolId(), e.getMessage());
            }
        }
    }

    private void restoreTool(ToolContract tool) {
        if (tool instanceof BuiltinTool builtinTool) {
            toolRegistry.registerBuiltinTool(builtinTool);
            return;
        }
        if (tool instanceof McpTool mcpTool) {
            toolRegistry.registerMcpTools(mcpTool.serverName(), List.of(mcpTool));
            return;
        }
        log.warn("未知工具类型，无法恢复: id={}, type={}", tool.id(), tool.getClass().getName());
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
     * 构建失败场景的评估结果（评分 0.0，错误信息记录到 violations）。
     *
     * @param scenario  场景定义
     * @param evalRunId 评估运行 ID
     * @param reason    失败原因
     * @return 失败评估结果
     */
    private EvalResult buildFailedResult(BenchmarkScenario scenario, String evalRunId, String reason) {
        var gitInfo = getGitInfo();
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
                .gitCommitHash(gitInfo.commitHash())
                .gitBranch(gitInfo.branch())
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
