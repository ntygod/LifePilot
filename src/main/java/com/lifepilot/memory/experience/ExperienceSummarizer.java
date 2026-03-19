package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.eval.model.EvalResult;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    private final LlmRouter llmRouter;
    private final PromptRegistry promptRegistry;
    private final MemoryProperties.Experience config;
    private final TrajectoryQualityAssessor qualityAssessor;

    public ExperienceSummarizer(SemanticMemory semanticMemory,
                                VectorSearcher vectorSearcher,
                                LlmRouter llmRouter,
                                PromptRegistry promptRegistry,
                                MemoryProperties properties,
                                TrajectoryQualityAssessor qualityAssessor) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.llmRouter = llmRouter;
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

    /** 检查触发条件：stepCount ≥ 2 且含至少一个 ToolCall。 */
    private boolean meetsTriggerConditions(ReactAgentState state) {
        if (state.stepCount() < 2) {
            log.debug("经验提炼: 步骤数不足, stepCount={}", state.stepCount());
            return false;
        }
        boolean hasToolCall = state.steps().stream()
                .anyMatch(s -> s instanceof ReactStep.ToolCall);
        if (!hasToolCall) {
            log.debug("经验提炼: 纯对话任务，无 ToolCall, sessionId={}", state.sessionId());
            return false;
        }
        return true;
    }

    /** 截断轨迹文本到 maxInputTokens（简单按字符估算）。 */
    private String truncateTrajectory(ReactAgentState state) {
        var sb = new StringBuilder();
        sb.append("目标: ").append(state.goal()).append("\n\n");

        for (var step : state.steps()) {
            switch (step) {
                case ReactStep.Thought t -> sb.append("[思考] ").append(t.content()).append("\n");
                case ReactStep.ToolCall tc -> sb.append("[工具调用] ").append(tc.toolId())
                        .append(" 输入: ").append(tc.inputJson()).append("\n");
                case ReactStep.Observation obs -> sb.append("[观察] ").append(obs.toolId())
                        .append(" 成功: ").append(obs.success())
                        .append(" 输出: ").append(obs.output()).append("\n");
                case ReactStep.Answer a -> sb.append("[回答] ").append(a.content()).append("\n");
                case ReactStep.Suspend s -> sb.append("[挂起] ").append(s.reason()).append("\n");
                case ReactStep.Resume r -> sb.append("[恢复] ").append(r.payload()).append("\n");
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
            var request = LlmRequest.of(LlmScene.CHAT, prompt);
            var record = llmRouter.callEntity(request, ExperienceRecord.class);

            // 校验返回结果
            if (record == null || record.scenario() == null || record.scenario().isBlank()
                    || record.strategy() == null || record.strategy().isBlank()) {
                log.debug("经验提炼: LLM 返回结果无效, sessionId={}", state.sessionId());
                return null;
            }
            return record;
        } catch (Exception e) {
            log.warn("经验提炼: LLM 调用失败, sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
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
                    semanticMemory.updateImportanceScore(existingId, boosted);
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

            semanticMemory.upsertWithConflictDetection(entity, sourceId);

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
}
