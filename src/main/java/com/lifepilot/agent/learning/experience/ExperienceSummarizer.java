package com.lifepilot.agent.learning.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.project.context.ProjectContextResolver;
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
    private final AgentLearningProperties.Experience config;
    private final TrajectoryQualityAssessor qualityAssessor;
    private final ChatSessionRepository chatSessionRepository;
    private final ProjectContextResolver projectContextResolver;

    public ExperienceSummarizer(SemanticMemory semanticMemory,
                                VectorSearcher vectorSearcher,
                                GenerationRouter generationRouter,
                                PromptRegistry promptRegistry,
                                AgentLearningProperties properties,
                                TrajectoryQualityAssessor qualityAssessor,
                                ChatSessionRepository chatSessionRepository,
                                ProjectContextResolver projectContextResolver) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        this.config = Objects.requireNonNull(properties, "properties 不能为空").getExperience();
        this.qualityAssessor = Objects.requireNonNull(qualityAssessor, "qualityAssessor 不能为空");
        this.chatSessionRepository = Objects.requireNonNull(chatSessionRepository, "chatSessionRepository 不能为空");
        this.projectContextResolver = Objects.requireNonNull(projectContextResolver, "projectContextResolver 不能为空");
    }

    /**
     * 从 ReactAgentState 提炼经验（主入口）。
     *
     * @param state Agent 最终状态
     * @return 新写入的经验实体，若未写入则返回 null
     */
    @Nullable
    public TemporalEntity summarize(ReactAgentState state) {
        Objects.requireNonNull(state, "Agent 状态不能为空");
        requireNonBlank(state.goal(), "经验提炼任务目标");
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
        var vars = Map.<String, Object>of(
                "goal", state.goal(),
                "trajectory", trajectoryText,
                "toolSuccessRatio", String.format("%.2f", report.toolSuccessRatio()),
                "taskSuccess", String.valueOf(report.taskSuccess())
        );
        String prompt = promptRegistry.render(PROMPT_KEY, vars);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("经验提炼 prompt 渲染结果不能为空");
        }
        Duration timeout = Duration.ofSeconds(positive(config.getLlmTimeoutSeconds(), "经验提炼 LLM 超时秒数"));
        log.debug("经验提炼: 发起 JSON 提炼调用, sessionId={}, timeoutSeconds={}, promptChars={}, stepCount={}",
                state.sessionId(), timeout.toSeconds(), prompt.length(), state.stepCount());
        LlmResponse response = generationRouter.call(
                LlmScene.BACKGROUND_ANALYSIS,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                timeout);
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("经验提炼 LLM 响应不能为空");
        }
        log.debug("经验提炼: JSON 提炼响应返回, sessionId={}, providerId={}, model={}, latencyMs={}, outputChars={}",
                state.sessionId(),
                response.providerId(),
                response.modelName(),
                response.latencyMs(),
                response.content() != null ? response.content().length() : 0);
        ExperienceRecordContract.validateLlmResponse(response.content(), "经验提炼");
        var record = JsonOutputParser.parse(response.content(), ExperienceRecord.class);

        // 校验返回结果
        if (record == null || record.scenario() == null || record.scenario().isBlank()
                || record.strategy() == null || record.strategy().isBlank()) {
            throw new IllegalStateException("经验提炼 LLM 响应缺少 scenario 或 strategy");
        }
        return record;
    }

    /** 持久化经验到 L3 语义记忆。去重命中时返回 null。 */
    @Nullable
    private TemporalEntity persistExperience(ExperienceRecord record, String sourceId, boolean success,
                                              @Nullable ReactAgentState state) {
        String experienceText = record.scenario() + ": " + record.strategy();

        // 去重检查
        var similar = requireVectorResults(vectorSearcher.searchEntities(experienceText, 1,
                similarityThreshold(config.getDedupSimilarityThreshold(), "经验提炼去重相似度阈值")));
        if (!similar.isEmpty()) {
            // 已有相似经验：提升 importanceScore
            String existingId = similar.getFirst().entityId();
            var existing = semanticMemory.findById(existingId);
            if (existing == null || existing.isEmpty()) {
                throw new IllegalStateException("经验提炼: 去重命中实体不存在: " + existingId);
            }
            float boosted = Math.min(existing.get().importanceScore() + 0.1f, 1.0f);
            SqliteBusyRetry.run(() -> semanticMemory.updateImportanceScore(
                    existingId, boosted, WeightSource.EFFECTIVENESS));
            log.debug("经验提炼: 去重命中，提升已有经验分数, entityId={}, newScore={}",
                    existingId, boosted);
            return null;
        }

        // 构建 TemporalEntity
        var now = Instant.now();
        String name = record.scenario().length() > 100
                ? record.scenario().substring(0, 100)
                : record.scenario();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lessons", record.lessons());
        props.put("applicableConditions", record.applicableConditions());
        props.put("toolsUsed", record.toolsUsed());
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

        float extractionConfidence = 0.8f;
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.LLM_SUMMARIZED_EXPERIENCE;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
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
                extractionConfidence,
                success ? 0.6f : 0.4f,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                evidenceKind,
                trustLevel,
                trustScore,
                1,
                null
        );

        MemoryWriteContext writeContext = resolveExperienceWriteContext(sourceId);
        SqliteBusyRetry.execute(() -> semanticMemory.upsertWithConflictDetection(entity, sourceId, writeContext));

        log.debug("经验提炼: 新经验已写入, entityId={}, name={}", entity.id(), name);
        return entity;
    }

    /**
     * 按 state.sessionId() 反查项目归属，构造经验写入上下文。
     *
     * <p>ISOLATED 项目 → spaceId=项目 space（经验落项目域）；
     * 主账户 / SHARED → spaceId=null，让 SemanticMemory 按 entity type 推断默认主账户 space。</p>
     *
     * <p>memoryScope 保持 null（同 RealtimeExtractor 的策略，交由下游按 entity type 推断）。</p>
     */
    private MemoryWriteContext resolveExperienceWriteContext(@Nullable String sessionId) {
        String spaceId = resolveProjectSpaceId(sessionId);
        return new MemoryWriteContext(
                spaceId,
                null,
                MemoryOriginType.CONSOLIDATION,
                MemoryRealityType.UNKNOWN,
                sessionId,
                sessionId,
                sessionId,
                null,
                null,
                null,
                null
        );
    }

    @Nullable
    private String resolveProjectSpaceId(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        java.util.Optional<ChatSession> session = chatSessionRepository.findById(sessionId);
        if (session.isEmpty()) {
            throw new IllegalStateException("无法找到会话，不能判定经验写入空间: " + sessionId);
        }
        String projectId = session.get().projectId();
        if (projectId == null) {
            return null;
        }
        var ctx = projectContextResolver.resolve(projectId);
        return ctx.isolated() ? ctx.projectSpaceId() : null;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }

    private static List<VectorSearchResult> requireVectorResults(List<VectorSearchResult> results) {
        if (results == null) {
            throw new IllegalStateException("经验提炼: 向量搜索结果不能为空");
        }
        for (VectorSearchResult result : results) {
            if (result == null) {
                throw new IllegalStateException("经验提炼: 向量搜索结果不能包含 null 元素");
            }
            requireCanonicalId(result.entityId(), "经验提炼向量候选 ID");
            if (!Float.isFinite(result.similarity())
                    || result.similarity() < 0.0f
                    || result.similarity() > 1.0f) {
                throw new IllegalStateException("经验提炼: 向量相似度必须在 [0,1] 范围内: "
                        + result.similarity());
            }
        }
        return results;
    }

    private static float similarityThreshold(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + "必须在 [0,1] 范围内: " + value);
        }
        return value;
    }

    private static void requireCanonicalId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

}
