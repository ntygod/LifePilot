package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 经验总结器 — 分析 ReAct 轨迹并生成结构化经验记录。
 *
 * <p>核心编排组件，协调数据质量评估、LLM 经验提炼、存储写入的完整流程。
 * 由 {@code ReactAgentLoop.asyncPostProcess} 在 Virtual Thread 中调用。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ExperienceSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ExperienceSummarizer.class);
    private static final String PROMPT_KEY = "memory/experience-extraction";

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final MemoryProperties.Experience config;
    private final TrajectoryQualityAssessor qualityAssessor;

    public ExperienceSummarizer(SemanticMemory semanticMemory,
                                VectorSearcher vectorSearcher,
                                GenerationRouter generationRouter,
                                PromptRegistry promptRegistry,
                                MemoryProperties properties,
                                TrajectoryQualityAssessor qualityAssessor) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = properties.getExperience();
        this.qualityAssessor = qualityAssessor;
    }

    /**
     * 从 ReactAgentState 提炼经验（主入口）。
     *
     * @param state Agent 最终状态
     * @return 新写入的经验实体，若未写入则返回 null
     */
    @Nullable
    public TemporalEntity summarize(ReactAgentState state) {
        // 1. 配置开关检查
        if (!config.isEnabled()) {
            log.debug("经验提炼: 功能已关闭");
            return null;
        }

        // 2. 触发条件检查
        if (!meetsTriggerConditions(state)) {
            return null;
        }

        // 3. 质量评估
        var report = qualityAssessor.assess(state);
        if (!report.qualityPassed()) {
            log.debug("经验提炼: 质量未通过, sessionId={}", state.sessionId());
            return null;
        }

        // 4. 截断轨迹
        String trajectoryText = truncateTrajectory(state);

        // 5. LLM 提炼
        var record = extractExperience(state, trajectoryText, report);
        if (record == null) {
            return null;
        }

        // 5.5 系统归因过滤：系统 bug 导致的失败不写入经验，避免错误经验污染
        if ("system".equals(record.failureAttribution())) {
            log.info("经验提炼: 跳过系统归因经验, sessionId={}, scenario={}",
                    state.sessionId(), record.scenario());
            return null;
        }

        // 6. 存储写入
        var entity = persistExperience(record, state.sessionId(), report.taskSuccess(), state);
        if (entity != null) {
            log.info("经验提炼: 完成, sessionId={}, scenario={}", state.sessionId(), record.scenario());
        }
        return entity;
    }

    /**
     * 从 Eval 批量评估结果中提炼经验。
     *
     * @param results   评估结果列表
     * @param scenarios 基准场景列表
     */
    public void summarizeFromEval(List<EvalResult> results, List<BenchmarkScenario> scenarios) {
        if (!config.isEnabled() || !config.isEvalIntegrationEnabled()) {
            log.debug("经验提炼: Eval 集成已关闭");
            return;
        }

        // 构建 scenarioId → BenchmarkScenario 映射
        var scenarioMap = new HashMap<String, BenchmarkScenario>();
        for (var s : scenarios) {
            scenarioMap.put(s.id(), s);
        }

        for (var evalResult : results) {
            try {
                var scenario = scenarioMap.get(evalResult.scenarioId());
                if (scenario == null) continue;

                // Eval 结果低于通过阈值时标记失败
                boolean success = evalResult.overallScore() >= 0.7;

                // 构建简化的经验记录
                var record = new ExperienceRecord(
                        scenario.userInput(),
                        success ? "Eval 评估通过的执行策略" : "Eval 评估未通过的执行策略",
                        evalResult.suggestions(),
                        appendEvalTags(List.of(), scenario.tags()),
                        List.of(),
                        success,
                        null,
                        0.0f,
                        0,
                        0,
                        0
                );

                if (record.scenario() == null || record.scenario().isBlank()) continue;

                persistExperience(record, evalResult.traceId(), success, null);
                log.info("经验提炼(Eval): 完成, scenarioId={}, score={}",
                        evalResult.scenarioId(), evalResult.overallScore());
            } catch (Exception e) {
                log.warn("经验提炼(Eval): 单条处理失败, evalId={}, error={}",
                        evalResult.evalId(), e.getMessage());
            }
        }
    }

    // ===== 内部方法 =====

    /**
     * 检查触发条件：过滤掉不值得提炼的简单交互。
     *
     * <p>经验的价值在于可迁移的策略知识，单工具直给（记偏好、查天气）不构成策略。
     * 满足以下任一条件才触发提炼：
     * <ul>
     *   <li>工具调用轮次 ≥ 2（多步推理）</li>
     *   <li>使用了 ≥ 2 种不同工具（能力组合）</li>
     *   <li>存在失败后恢复（试错策略）</li>
     * </ul>
     */
    private boolean meetsTriggerConditions(ReactAgentState state) {
        var toolCalls = state.steps().stream()
                .filter(s -> s instanceof ReactStep.ToolCall)
                .map(s -> (ReactStep.ToolCall) s)
                .toList();

        if (toolCalls.isEmpty()) {
            log.debug("经验提炼: 纯对话任务，无 ToolCall, sessionId={}", state.sessionId());
            return false;
        }

        // 条件 1：工具调用轮次 ≥ 2
        if (toolCalls.size() >= 2) {
            return true;
        }

        // 条件 2：使用了 ≥ 2 种不同工具
        long distinctTools = toolCalls.stream().map(ReactStep.ToolCall::toolId).distinct().count();
        if (distinctTools >= 2) {
            return true;
        }

        // 条件 3：存在失败后恢复（有失败的 Observation 但任务最终成功）
        boolean hasFailedObservation = state.steps().stream()
                .anyMatch(s -> s instanceof ReactStep.Observation obs && !obs.success());
        if (hasFailedObservation && state.terminationReason() == null) {
            return true;
        }

        log.debug("经验提炼: 单工具直给，无策略价值, sessionId={}, toolId={}",
                state.sessionId(), toolCalls.getFirst().toolId());
        return false;
    }

    /** 截断轨迹文本到 maxInputTokens（简单按字符估算）。 */
    private String truncateTrajectory(ReactAgentState state) {
        var sb = new StringBuilder();
        sb.append("目标: ").append(state.goal()).append("\n\n");

        for (var step : state.steps()) {
            switch (step) {
                case ReactStep.Progress p -> sb.append("[进度] ").append(p.content()).append("\n");
                case ReactStep.Thought t -> sb.append("[思考] ").append(t.content()).append("\n");
                case ReactStep.ToolCall tc -> sb.append("[工具调用] ").append(tc.toolId())
                        .append(" 输入: ").append(tc.inputJson()).append("\n");
                case ReactStep.Observation obs -> sb.append("[观察] ").append(obs.toolId())
                        .append(" 成功: ").append(obs.success())
                        .append(" 输出: ").append(obs.output()).append("\n");
                case ReactStep.Answer a -> sb.append("[回答] ").append(a.content()).append("\n");
                case ReactStep.Suspend s -> sb.append("[挂起] ").append(s.reason()).append("\n");
                case ReactStep.Resume r -> sb.append("[恢复] ").append(r.payload()).append("\n");
                case ReactStep.Reflect ref -> sb.append("[回顾] ").append(ref.content()).append("\n");
            }
        }

        if (state.finalOutput() != null) {
            sb.append("\n最终输出: ").append(state.finalOutput());
        }

        String text = sb.toString();
        // 简单按字符估算 token（中文约 1 字符 ≈ 1.5 token，取保守值 1 字符 ≈ 1 token）
        int maxChars = config.getMaxInputTokens();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n...(已截断)";
        }
        return text;
    }

    /** 调用 LLM 提炼经验。 */
    @Nullable
    private ExperienceRecord extractExperience(ReactAgentState state, String trajectoryText,
                                                TrajectoryQualityReport report) {
        try {
            var vars = Map.<String, Object>of(
                    "goal", state.goal(),
                    "trajectory", trajectoryText,
                    "toolSuccessRatio", String.format("%.2f", report.toolSuccessRatio()),
                    "taskSuccess", String.valueOf(report.taskSuccess())
            );
            String prompt = promptRegistry.render(PROMPT_KEY, vars);
            Duration timeout = Duration.ofSeconds(Math.max(1, config.getLlmTimeoutSeconds()));
            log.debug("经验提炼: 发起 JSON 提炼调用, sessionId={}, timeoutSeconds={}, promptChars={}, stepCount={}",
                    state.sessionId(), timeout.toSeconds(), prompt.length(), state.stepCount());
            LlmResponse response = generationRouter.call(
                    LlmScene.CHAT,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    timeout);
            log.debug("经验提炼: JSON 提炼响应返回, sessionId={}, providerId={}, model={}, latencyMs={}, outputChars={}",
                    state.sessionId(),
                    response.providerId(),
                    response.modelName(),
                    response.latencyMs(),
                    response.content() != null ? response.content().length() : 0);
            var record = JsonOutputParser.parse(response.content(), ExperienceRecord.class);

            // 校验返回结果
            if (record == null || record.scenario() == null || record.scenario().isBlank()
                    || record.strategy() == null || record.strategy().isBlank()) {
                log.debug("经验提炼: LLM 返回结果无效, sessionId={}", state.sessionId());
                return null;
            }
            return record;
        } catch (Exception e) {
            log.warn("经验提炼: JSON 提炼失败, sessionId={}, timeoutSeconds={}, errorType={}, error={}",
                    state.sessionId(), config.getLlmTimeoutSeconds(), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 持久化经验到 L3 语义记忆。去重命中或异常时返回 null。 */
    @Nullable
    private TemporalEntity persistExperience(ExperienceRecord record, String sourceId, boolean success,
                                              @Nullable ReactAgentState state) {
        try {
            String experienceText = record.scenario() + ": " + record.strategy();

            // 去重检查
            var similar = vectorSearcher.searchEntities(experienceText, 1,
                    config.getDedupSimilarityThreshold());
            if (!similar.isEmpty()) {
                // 已有相似经验：提升 importanceScore
                String existingId = similar.getFirst().entityId();
                semanticMemory.findById(existingId).ifPresent(existing -> {
                    float boosted = Math.min(existing.importanceScore() + 0.1f, 1.0f);
                    retryOnBusy(() -> { semanticMemory.updateImportanceScore(existingId, boosted); return null; });
                    log.debug("经验提炼: 去重命中，提升已有经验分数, entityId={}, newScore={}",
                            existingId, boosted);
                });
                return null;
            }

            // 构建 TemporalEntity
            var now = Instant.now();
            String name = record.scenario().length() > 100
                    ? record.scenario().substring(0, 100)
                    : record.scenario();

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("lessons", record.lessons() != null ? record.lessons() : List.of());
            props.put("applicableConditions", record.applicableConditions() != null
                    ? record.applicableConditions() : List.of());
            props.put("toolsUsed", record.toolsUsed() != null ? record.toolsUsed() : List.of());
            props.put("success", record.success());

            // 执行上下文标记
            if (state != null) {
                ExecutionContext ctx = ExecutionContext.infer(state);
                props.put("executionContext", ctx.name());
                if (ctx == ExecutionContext.EVAL && state.sessionId() != null) {
                    props.put("evalRunId", state.sessionId());
                }
            }

            // 初始化效果追踪字段默认值
            props.put("effectivenessScore", 0.0f);
            props.put("injectionCount", 0);
            props.put("positiveOutcomes", 0);
            props.put("negativeOutcomes", 0);

            var entity = new TemporalEntity(
                    UUID.randomUUID().toString(),
                    EntityType.EXPERIENCE,
                    name,
                    record.strategy(),
                    props,
                    1,
                    true,
                    now,
                    null,
                    sourceId,
                    0.8f,
                    success ? 0.6f : 0.4f,
                    0,
                    null,
                    now,
                    now
            );

            retryOnBusy(() -> semanticMemory.upsertWithConflictDetection(entity, sourceId));

            // 更新向量索引
            vectorSearcher.upsertEntityVector(entity.id(), experienceText);

            log.debug("经验提炼: 新经验已写入, entityId={}, name={}", entity.id(), name);
            return entity;
        } catch (Exception e) {
            log.warn("经验提炼: 存储写入失败, scenario={}, error={}",
                    record.scenario(), e.getMessage());
            return null;
        }
    }

    /** 追加 eval 标签前缀到 applicableConditions。 */
    private List<String> appendEvalTags(List<String> conditions, List<String> tags) {
        if (tags == null || tags.isEmpty()) return conditions;
        var result = new ArrayList<>(conditions);
        for (var tag : tags) {
            result.add(config.getEvalTagPrefix() + tag);
        }
        return List.copyOf(result);
    }

    /**
     * SQLite BUSY 重试：指数退避，最多重试 3 次。
     *
     * <p>SQLite WAL 模式下并发写入可能触发 SQLITE_BUSY_SNAPSHOT，
     * 此方法在事务外层重试，确保每次重试使用新的事务和快照。</p>
     */
    private <T> T retryOnBusy(java.util.function.Supplier<T> operation) {
        int maxRetries = 3;
        long baseDelayMs = 200;
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (attempt >= maxRetries || !isSqliteBusy(e)) {
                    throw e;
                }
                long delay = baseDelayMs * (1L << attempt);
                log.debug("经验提炼: SQLite BUSY 重试, attempt={}, delayMs={}", attempt + 1, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 判断异常链中是否包含 SQLite BUSY 错误。 */
    private boolean isSqliteBusy(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.sqlite.SQLiteException sqliteEx && sqliteEx.getResultCode() != null
                    && sqliteEx.getResultCode().name().startsWith("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }
}
