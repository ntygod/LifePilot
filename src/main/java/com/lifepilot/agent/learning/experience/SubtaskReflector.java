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
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.consumption.compression.TokenEstimator;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.store.entity.EntityType;
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
 * 子任务反思器 — 从连续工具调用序列中提取细粒度的工具使用经验。
 *
 * <p>在 asyncPostProcess 的 Virtual Thread 中调用，从 ToolCall → Observation 步骤对中
 * 提取"工具选择是否正确"、"参数是否最优"、"调用顺序是否合理"三个维度的微观经验。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class SubtaskReflector {

    private static final Logger log = LoggerFactory.getLogger(SubtaskReflector.class);
    private static final String PROMPT_KEY = "memory/subtask-reflection";

    /** 工具级经验粒度标识 — 在 ContextAssembler、ToolTipResolver 中共享引用。 */
    public static final String TOOL_LEVEL = "TOOL_LEVEL";

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final AgentLearningProperties.Experience.Subtask config;
    private final float dedupThreshold;
    private final ChatSessionRepository chatSessionRepository;
    private final ProjectContextResolver projectContextResolver;

    public SubtaskReflector(SemanticMemory semanticMemory,
                            VectorSearcher vectorSearcher,
                            GenerationRouter generationRouter,
                            PromptRegistry promptRegistry,
                            AgentLearningProperties AgentLearningProperties,
                            ChatSessionRepository chatSessionRepository,
                            ProjectContextResolver projectContextResolver) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        var properties = Objects.requireNonNull(AgentLearningProperties, "AgentLearningProperties 不能为空");
        this.config = properties.getExperience().getSubtask();
        this.dedupThreshold = properties.getExperience().getDedupSimilarityThreshold();
        this.chatSessionRepository = Objects.requireNonNull(chatSessionRepository, "chatSessionRepository 不能为空");
        this.projectContextResolver = Objects.requireNonNull(projectContextResolver, "projectContextResolver 不能为空");
    }

    /**
     * 从 ReactAgentState 中提取子任务级经验。
     * 在 asyncPostProcess 的 Virtual Thread 中调用。
     *
     * @param state Agent 最终状态
     */
    public void reflect(ReactAgentState state) {
        Objects.requireNonNull(state, "Agent 状态不能为空");
        requireNonBlank(state.goal(), "子任务反思任务目标");
        if (!config.isEnabled()) {
            log.debug("子任务反思: 功能已关闭");
            return;
        }

        // 提取连续 ToolCall → Observation 步骤对
        var pairs = extractToolObservationPairs(state.steps());

        // 检查序列长度
        if (pairs.size() < config.getMinToolSequence()) {
            log.debug("子任务反思: 工具调用序列长度不足, size={}, min={}",
                    pairs.size(), config.getMinToolSequence());
            return;
        }

        // 检查至少一个成功 Observation
        boolean hasSuccess = pairs.stream()
                .anyMatch(p -> p.observation().success());
        if (!hasSuccess) {
            log.debug("子任务反思: 无成功 Observation，跳过");
            return;
        }

        // 格式化工具序列并截断
        String formattedSequence = formatToolSequence(pairs);

        // 渲染提示词
        String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                "goal", state.goal(),
                "toolSequence", formattedSequence
        ));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("子任务反思 prompt 渲染结果不能为空");
        }

        // 调用 LLM
        Duration timeout = Duration.ofSeconds(positive(config.getLlmTimeoutSeconds(), "子任务反思 LLM 超时秒数"));
        log.debug("子任务反思: 发起 JSON 反思调用, sessionId={}, timeoutSeconds={}, promptChars={}, sequenceSize={}",
                state.sessionId(), timeout.toSeconds(), prompt.length(), pairs.size());
        LlmResponse response = generationRouter.call(
                LlmScene.BACKGROUND_ANALYSIS,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                timeout);
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("子任务反思 LLM 响应不能为空");
        }
        log.debug("子任务反思: JSON 反思响应返回, sessionId={}, providerId={}, model={}, latencyMs={}, outputChars={}",
                state.sessionId(),
                response.providerId(),
                response.modelName(),
                response.latencyMs(),
                response.content() != null ? response.content().length() : 0);
        ExperienceRecordContract.validateLlmResponse(response.content(), "子任务反思");
        ExperienceRecord record = JsonOutputParser.parse(response.content(), ExperienceRecord.class);

        // 验证结果
        if (record == null || record.scenario() == null || record.scenario().isBlank()
                || record.strategy() == null || record.strategy().isBlank()) {
            throw new IllegalStateException("子任务反思 LLM 响应缺少 scenario 或 strategy");
        }

        // 去重检查
        String textRepresentation = record.scenario() + ": " + record.strategy();
        var similar = requireVectorResults(vectorSearcher.searchEntities(
                textRepresentation, 1, similarityThreshold(dedupThreshold, "子任务反思去重相似度阈值")));
        if (!similar.isEmpty()) {
            String existingId = similar.getFirst().entityId();
            var existing = semanticMemory.findById(existingId);
            if (existing == null || existing.isEmpty()) {
                throw new IllegalStateException("子任务反思: 去重命中实体不存在: " + existingId);
            }
            log.debug("子任务反思: 去重命中，跳过, similarEntityId={}",
                    existingId);
            return;
        }

        // 构建并写入 TemporalEntity
        persistSubtaskExperience(record, state);
    }

    /**
     * 提取连续的 ToolCall → Observation 步骤对。
     *
     * @param steps 所有步骤列表
     * @return 连续的步骤对列表
     */
    private List<ToolObservationPair> extractToolObservationPairs(List<ReactStep> steps) {
        var pairs = new ArrayList<ToolObservationPair>();
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i) instanceof ReactStep.ToolCall tc
                    && steps.get(i + 1) instanceof ReactStep.Observation obs) {
                pairs.add(new ToolObservationPair(tc, obs));
            }
        }
        return pairs;
    }

    /**
     * 格式化工具调用序列为文本，截断到 maxInputTokens。
     *
     * @param pairs 步骤对列表
     * @return 格式化后的文本
     */
    private String formatToolSequence(List<ToolObservationPair> pairs) {
        var sb = new StringBuilder();
        int maxTokens = config.getMaxInputTokens();

        for (var pair : pairs) {
            String line = String.format("ToolCall: %s(%s) → Observation: %s: %s\n",
                    pair.toolCall().toolId(),
                    pair.toolCall().inputJson(),
                    pair.observation().success() ? "成功" : "失败",
                    pair.observation().output());

            // 估算 Token：统一口径 TokenEstimator
            int currentTokens = TokenEstimator.estimate(sb.toString());
            int lineTokens = TokenEstimator.estimate(line);

            if (currentTokens + lineTokens > maxTokens) {
                // 截断：尝试添加部分内容
                int remainingTokens = maxTokens - currentTokens;
                if (remainingTokens > 0) {
                    int maxChars = remainingTokens * 4;
                    sb.append(line, 0, Math.min(line.length(), maxChars));
                }
                break;
            }
            sb.append(line);
        }

        return sb.toString();
    }

    /** 持久化子任务经验实体。 */
    private void persistSubtaskExperience(ExperienceRecord record, ReactAgentState state) {
        var now = Instant.now();
        // 加上 [工具] 前缀以区分工具级经验
        String scenarioText = record.scenario();
        String name = "[工具] " + (scenarioText.length() > 94 ? scenarioText.substring(0, 94) : scenarioText);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lessons", record.lessons());
        props.put("applicableConditions", record.applicableConditions());
        props.put("toolsUsed", record.toolsUsed());
        props.put("success", record.success());
        props.put("subtask", true);
        // 工具级经验标记：粒度 + 主工具 ID
        props.put("granularity", TOOL_LEVEL);
        String primaryToolId = !record.toolsUsed().isEmpty() ? record.toolsUsed().getFirst() : null;
        props.put("toolId", primaryToolId);
        props.put("executionContext", ExecutionContext.infer(state).name());
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
                null,
                extractionConfidence,
                config.getInitialImportance(),
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

        MemoryWriteContext writeContext = resolveWriteContext(state.sessionId());
        SqliteBusyRetry.run(() -> semanticMemory.upsertWithConflictDetection(entity, "subtask-reflection", writeContext));

        log.info("子任务反思: 子任务经验已写入, entityId={}, name={}", entity.id(), name);
    }

    /**
     * 构造子任务经验写入上下文。
     *
     * <p>ISOLATED 项目 → spaceId=项目 space；主账户/SHARED → spaceId=null
     * 让 SemanticMemory 按 entity type 推断默认 space。memoryScope 保持 null。</p>
     */
    private MemoryWriteContext resolveWriteContext(@Nullable String sessionId) {
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
            throw new IllegalStateException("无法找到会话，不能判定子任务经验写入空间: " + sessionId);
        }
        String projectId = session.get().projectId();
        if (projectId == null) {
            return null;
        }
        var ctx = projectContextResolver.resolve(projectId);
        return ctx.isolated() ? ctx.projectSpaceId() : null;
    }

    /** ToolCall → Observation 步骤对。 */
    private record ToolObservationPair(ReactStep.ToolCall toolCall, ReactStep.Observation observation) {}

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }

    private static List<VectorSearchResult> requireVectorResults(List<VectorSearchResult> results) {
        if (results == null) {
            throw new IllegalStateException("子任务反思: 向量搜索结果不能为空");
        }
        for (VectorSearchResult result : results) {
            if (result == null) {
                throw new IllegalStateException("子任务反思: 向量搜索结果不能包含 null 元素");
            }
            requireCanonicalId(result.entityId(), "子任务反思向量候选 ID");
            if (!Float.isFinite(result.similarity())
                    || result.similarity() < 0.0f
                    || result.similarity() > 1.0f) {
                throw new IllegalStateException("子任务反思: 向量相似度必须在 [0,1] 范围内: "
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
        requireNonBlank(value, label);
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
