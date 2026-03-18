package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.QueryRefiner;
import com.lifepilot.memory.retrieval.QueryRewriter;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.working.*;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 完整版上下文组装器 — 集成记忆检索、会话上下文、动态预算分配。
 *
 * <p>组装流程：
 * <ol>
 *   <li>根据 AgentPhase 获取检索策略</li>
 *   <li>通过 HybridRetriever 执行三路混合检索</li>
 *   <li>从 WorkingMemory 获取会话槽位</li>
 *   <li>通过 TokenBudgetAllocator 动态分配预算</li>
 *   <li>按预算截断检索结果和会话槽位</li>
 *   <li>构建结构化 User Prompt</li>
 * </ol></p>
 *
 * <p>降级策略：任何记忆组件异常时优雅降级为空结果，保证 assemble() 不抛出异常。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private final AgentConfigProperties config;
    @Nullable private final HybridRetriever hybridRetriever;
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final TokenBudgetAllocator tokenBudgetAllocator;
    @Nullable private final MemoryRetrievalStrategy retrievalStrategy;
    @Nullable private final DataRedactor dataRedactor;
    private final PromptRegistry promptRegistry;
    // L2 情景记忆：跨会话语义检索，可选注入
    @Nullable private final EpisodicMemory episodicMemory;
    // L3 语义记忆：用户画像查询，可选注入
    @Nullable private final SemanticMemory semanticMemory;
    // 知识库（文档）检索：可选注入，未启用时不影响主流程
    @Nullable private final DocumentRetriever documentRetriever;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final DocumentRepository documentRepository;
    // 被动通知队列：首次对话时 drain 并注入上下文，可选注入
    @Nullable private final PassiveNotificationQueue passiveNotificationQueue;
    // 查询精炼器：清洗用户输入提升检索召回质量，可选注入
    @Nullable private final QueryRefiner queryRefiner;
    // 记忆配置：用户画像查询等参数，可选注入
    @Nullable private final com.lifepilot.memory.config.MemoryProperties memoryProperties;
    // LLM 路由器：用于跨会话语义过滤的 embedding 计算，可选注入
    @Nullable private final com.lifepilot.llm.LlmRouter llmRouter;
    // 查询改写器：LLM 语义改写提升检索召回，可选注入
    @Nullable private final QueryRewriter queryRewriter;

    /** 请求级检索缓存 — 同一 traceId + query + topK 组合只执行一次实际检索。 */
    private final ConcurrentHashMap<String, List<RetrievalResult>> retrievalCache = new ConcurrentHashMap<>();

    /** 上一轮查询的 embedding 缓存（用于话题切换检测）。 */
    private volatile float[] lastQueryEmbedding;

    /** 基础版构造器（向后兼容，记忆字段为 null）。 */
    public ContextAssembler(AgentConfigProperties config, PromptRegistry promptRegistry) {
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.hybridRetriever = null;
        this.workingMemory = null;
        this.tokenBudgetAllocator = null;
        this.retrievalStrategy = null;
        this.dataRedactor = null;
        this.episodicMemory = null;
        this.semanticMemory = null;
        this.documentRetriever = null;
        this.sessionKnowledgeBaseRepository = null;
        this.documentRepository = null;
        this.passiveNotificationQueue = null;
        this.queryRefiner = null;
        this.memoryProperties = null;
        this.llmRouter = null;
        this.queryRewriter = null;
    }

    /** 完整版构造器（注入记忆系统依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            HybridRetriever hybridRetriever,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            MemoryRetrievalStrategy retrievalStrategy,
                            @Nullable DataRedactor dataRedactor,
                            PromptRegistry promptRegistry) {
        this(config, hybridRetriever, workingMemory, tokenBudgetAllocator, retrievalStrategy, dataRedactor,
                null, null, null, null, null, null, null, null, null, null, promptRegistry);
    }

    /** 完整版构造器（注入记忆系统 + 可选知识库检索 + 可选 L2 情景记忆 + 可选 L3 语义记忆 + 可选被动通知队列 + 可选查询精炼器 + 可选 LlmRouter + 可选 QueryRewriter 依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            HybridRetriever hybridRetriever,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            MemoryRetrievalStrategy retrievalStrategy,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable DocumentRetriever documentRetriever,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable DocumentRepository documentRepository,
                            @Nullable EpisodicMemory episodicMemory,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable PassiveNotificationQueue passiveNotificationQueue,
                            @Nullable QueryRefiner queryRefiner,
                            @Nullable com.lifepilot.memory.config.MemoryProperties memoryProperties,
                            @Nullable com.lifepilot.llm.LlmRouter llmRouter,
                            @Nullable QueryRewriter queryRewriter,
                            PromptRegistry promptRegistry) {
        this.config = config;
        this.hybridRetriever = hybridRetriever;
        this.workingMemory = workingMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.retrievalStrategy = retrievalStrategy;
        this.dataRedactor = dataRedactor;
        this.promptRegistry = promptRegistry;
        this.episodicMemory = episodicMemory;
        this.semanticMemory = semanticMemory;
        this.documentRetriever = documentRetriever;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.passiveNotificationQueue = passiveNotificationQueue;
        this.queryRefiner = queryRefiner;
        this.memoryProperties = memoryProperties;
        this.llmRouter = llmRouter;
        this.queryRewriter = queryRewriter;
    }

    /** 判断是否为完整版模式。 */
    private boolean isFullMode() {
        return hybridRetriever != null && workingMemory != null
                && tokenBudgetAllocator != null && retrievalStrategy != null;
    }

    /**
     * 根据当前状态组装上下文 — 主入口。
     *
     * @param state 当前 Agent 状态
     * @return 增强版 AssembledContext
     */
    public AssembledContext assemble(ReactAgentState state) {
        // 基础版走原有逻辑
        if (!isFullMode()) {
            return assembleBasic(state);
        }

        var startTime = Instant.now();
        boolean degraded = false;

        try {
            // 1. 获取检索策略（ReAct 架构无阶段区分，使用默认策略）
            var strategyConfig = retrievalStrategy.getDefaultStrategy();

            if (strategyConfig.skip()) {
                return buildMinimalContext(state);
            }

            // 1.1 媒体占位符检测：音频/视频消息的 goal 是占位字符，跳过无效向量检索
            if (isMediaPlaceholderQuery(state.goal())) {
                log.debug("检测到媒体占位符查询，跳过向量检索: sessionId={}, goal={}",
                        state.sessionId(), state.goal());
                return assembleForMediaPlaceholder(state);
            }

            // 1.5 查询精炼：清洗用户输入提升检索召回质量
            String refinedQuery = safeRefineQuery(state.goal());

            // 1.6 话题切换检测：切换时清除缓存，降低跨会话注入权重
            boolean topicSwitched = detectTopicSwitch(refinedQuery);

            // 2. 四路并行检索（Virtual Thread）— 使用精炼后的查询
            List<RetrievalResult> retrievalResults;
            List<String> kbSnippets;
            List<WorkingMemorySlot> slots;
            List<MessageRecord> crossSessionFragments;
            Optional<ReasoningSlot> procedureHintSlot;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var retrievalFuture = CompletableFuture.supplyAsync(
                        () -> safeRewriteAndRetrieve(state.traceId(), refinedQuery, strategyConfig), executor);
                var kbFuture = CompletableFuture.supplyAsync(
                        () -> safeRetrieveKnowledgeBaseSnippets(state.sessionId(), refinedQuery, 5), executor);
                var slotsFuture = CompletableFuture.supplyAsync(
                        () -> safeGetSessionHistory(workingMemory, state.sessionId(), state.goal()), executor);
                var crossSessionFuture = CompletableFuture.supplyAsync(
                        () -> safeSearchCrossSession(episodicMemory, refinedQuery, state.sessionId()), executor);

                CompletableFuture.allOf(retrievalFuture, kbFuture, slotsFuture, crossSessionFuture).join();

                retrievalResults = retrievalFuture.join();
                kbSnippets = kbFuture.join();
                slots = slotsFuture.join();
                crossSessionFragments = crossSessionFuture.join();
            } catch (Exception parallelEx) {
                // Virtual Thread 创建失败时降级为串行执行
                log.warn("并行检索异常，降级为串行: error={}", parallelEx.getMessage());
                retrievalResults = safeRewriteAndRetrieve(state.traceId(), refinedQuery, strategyConfig);
                kbSnippets = safeRetrieveKnowledgeBaseSnippets(state.sessionId(), refinedQuery, 5);
                slots = safeGetSessionHistory(workingMemory, state.sessionId(), state.goal());
                crossSessionFragments = safeSearchCrossSession(episodicMemory, refinedQuery, state.sessionId());
            }

            // L4: 可选意图匹配提示（来自 HybridRetriever 内部的 IntentMatcher 结果）
            procedureHintSlot = safeGetLastProcedureSlot(hybridRetriever);
            int retrievalCount = retrievalResults.size();
            float topScore = retrievalResults.isEmpty() ? 0.0f
                    : retrievalResults.getFirst().fusedScore();
            if (retrievalResults.isEmpty() && state.goal() != null) {
                // 检索返回空是正常状态（新系统/首次对话），不标记降级
                log.debug("记忆检索无结果: sessionId={}, goal={}", state.sessionId(), truncate(state.goal(), 50));
            }

            // 4. 动态预算分配（降级容错）— 优先使用会话级 Token 窗口
            int conversationTurns = countConversationTurns(slots);
            int sessionMaxTokens = state.budget() != null ? state.budget().maxTokens() : 0;
            var budgetAllocation = safeAllocate(tokenBudgetAllocator, conversationTurns, topScore, !retrievalResults.isEmpty(), sessionMaxTokens);

            // 5. 按预算截断
            var truncatedMemories = truncateByBudget(retrievalResults, budgetAllocation.knowledgeEntityBudget());
            var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.currentSessionBudget());
            // 5.1 跨会话语义过滤 + 格式化并按预算截断
            crossSessionFragments = filterBySemanticSimilarity(crossSessionFragments, refinedQuery);
            int crossSessionBudget = budgetAllocation.crossSessionBudget();
            if (topicSwitched) {
                crossSessionBudget = crossSessionBudget / 2;
                log.debug("话题切换: 跨会话预算减半, budget={}", crossSessionBudget);
            }
            var formattedCrossSession = truncateStringsByBudget(
                    formatCrossSessionFragments(crossSessionFragments),
                    crossSessionBudget);

            // 5.2 对最终注入上下文的结果更新 accessCount（截断后而非检索时）
            safeUpdateAccessCounts(truncatedMemories);

            // 6. 将 L4 程序提示注入 L1（ReasoningSlot），并格式化检索结果
            slots = injectProcedureReasoningSlot(workingMemory, state.sessionId(), procedureHintSlot, slots);
            var formattedMemories = formatRetrievalResults(truncatedMemories);
            int workingMemoryTokens = truncatedSlots.stream()
                    .mapToInt(WorkingMemorySlot::tokenCount).sum();

            // 7. 构建 systemPrompt（ReAct 架构使用通用角色定义）
            String systemPrompt = buildReactSystemPrompt();

            // 8. 构建 TokenBudget（ReAct 架构使用默认分配）
            var tokenBudget = buildTokenBudgetDefault(budgetAllocation,
                    formattedMemories, truncatedSlots, systemPrompt);
            // 用户画像从 System Prompt 移至 User Prompt 半稳定区
            String userProfile = safeGetUserProfile(semanticMemory, refinedQuery);
            String userPrompt = buildEnhancedUserPrompt(state, formattedMemories, kbSnippets,
                    formattedCrossSession, truncatedSlots, userProfile);

            var context = new AssembledContext(
                    systemPrompt, userPrompt, formattedMemories,
                    tokenBudget, retrievalCount, topScore,
                    workingMemoryTokens, degraded,
                    truncatedMemories.stream()
                            .map(RetrievalResult::entityId)
                            .toList(),
                    null);

            // 9. 各区域 Token 消耗明细日志
            if (log.isDebugEnabled()) {
                int currentSessionTokens = truncatedSlots.stream()
                        .mapToInt(WorkingMemorySlot::tokenCount).sum();
                int crossSessionTokens = formattedCrossSession.stream()
                        .mapToInt(this::estimateTokens).sum();
                int knowledgeEntityTokens = formattedMemories.stream()
                        .mapToInt(this::estimateTokens).sum();
                int kbSnippetTokens = kbSnippets.stream()
                        .mapToInt(this::estimateTokens).sum();
                int systemPromptTokens = estimateTokens(systemPrompt);
                log.debug("各区域 Token 消耗: sessionId={}, systemPrompt={}, currentSession={}, crossSession={}, knowledgeEntity={}, knowledgeBase={}",
                        state.sessionId(), systemPromptTokens, currentSessionTokens,
                        crossSessionTokens, knowledgeEntityTokens, kbSnippetTokens);
            }
            logAssemblyMetrics(state, context, startTime);

            return context;

        } catch (Exception e) {
            log.warn("上下文组装异常，降级为基础版: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return buildFallbackContext(state);
        }
    }

    // --- 基础版逻辑 ---

    /** 基础版组装（无记忆检索）。 */
    private AssembledContext assembleBasic(ReactAgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocateDefault(totalTokens);
        String systemPrompt = safeReactSystemPrompt();
        String userPrompt = buildUserPrompt(state);
        return new AssembledContext(systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, 0, false, List.of(), null);
    }

    /** 返回最小化上下文。 */
    private AssembledContext buildMinimalContext(ReactAgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocateDefault(totalTokens);
        return new AssembledContext("", "", List.of(), tokenBudget,
                0, 0.0f, 0, false, List.of(), null);
    }

    /** 异常兜底降级上下文。 */
    private AssembledContext buildFallbackContext(ReactAgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocateDefault(totalTokens);
        String systemPrompt = safeReactSystemPrompt();
        String userPrompt = buildUserPrompt(state);
        return new AssembledContext(systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, 0, true, List.of(), null);
    }

    /**
     * 检测 goal 是否为媒体占位符查询。
     *
     * <p>当用户发送语音/视频消息且启用原生音频路由时，前端发送的 content 是占位字符
     * （如 {@code [语音消息]}、{@code [视频消息]}），或者用户仅上传附件未输入文字时 goal 为空。
     * 这些占位符对向量检索毫无意义，应跳过整个检索管线。</p>
     *
     * @param goal 用户输入（可能为 null）
     * @return 如果是媒体占位符则返回 true
     */
    boolean isMediaPlaceholderQuery(@Nullable String goal) {
        if (goal == null || goal.isBlank()) {
            return true;
        }
        String trimmed = goal.trim();
        // 匹配方括号包裹的短占位符，如 [语音消息]、[视频消息]、[图片]
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() <= 20) {
            return true;
        }
        return false;
    }

    /**
     * 媒体占位符专用组装 — 仅获取会话历史 + 系统提示 + 用户提示，跳过所有向量检索。
     *
     * <p>保留 L1 会话上下文和被动通知，让 LLM 能结合对话历史理解音频/视频内容，
     * 但不执行查询精炼、话题切换检测、记忆检索、知识库检索、跨会话检索、用户画像查询等。</p>
     */
    private AssembledContext assembleForMediaPlaceholder(ReactAgentState state) {
        // 仅获取 L1 会话历史
        var slots = safeGetSessionHistory(workingMemory, state.sessionId(), state.goal());

        // 动态预算分配（无检索结果）
        int conversationTurns = countConversationTurns(slots);
        int sessionMaxTokens = state.budget() != null ? state.budget().maxTokens() : 0;
        var budgetAllocation = safeAllocate(tokenBudgetAllocator, conversationTurns,
                0.0f, false, sessionMaxTokens);

        var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.currentSessionBudget());
        int workingMemoryTokens = truncatedSlots.stream()
                .mapToInt(WorkingMemorySlot::tokenCount).sum();

        String systemPrompt = buildReactSystemPrompt();
        var tokenBudget = buildTokenBudgetDefault(budgetAllocation,
                List.of(), truncatedSlots, systemPrompt);

        // 构建 userPrompt：无记忆/知识库/跨会话/用户画像，仅保留会话历史和被动通知
        String userPrompt = buildEnhancedUserPrompt(state, List.of(), List.of(),
                List.of(), truncatedSlots, null);

        return new AssembledContext(
                systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, workingMemoryTokens, false, List.of(), null);
    }

    // --- 降级容错方法 ---

    /**
     * 安全执行查询精炼，异常时降级返回原始输入。
     *
     * @param rawGoal 用户原始输入
     * @return 精炼后的查询文本，精炼失败时返回原始输入
     */
    private String safeRefineQuery(String rawGoal) {
        if (queryRefiner == null || rawGoal == null || rawGoal.isBlank()) {
            return rawGoal;
        }
        try {
            String refined = queryRefiner.refine(rawGoal);
            if (refined == null || refined.isBlank()) {
                return rawGoal;
            }
            if (!refined.equals(rawGoal) && log.isDebugEnabled()) {
                log.debug("查询精炼: 原始={}, 精炼后={}", truncate(rawGoal, 50), truncate(refined, 50));
            }
            return refined;
        } catch (Exception e) {
            log.warn("查询精炼失败，降级使用原始输入: error={}", e.getMessage());
            return rawGoal;
        }
    }

    /** 安全执行记忆检索，异常时返回空列表。 */
    private List<RetrievalResult> safeRetrieve(HybridRetriever retriever, String query, RetrievalStrategyConfig config) {
        try {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            return retriever.retrieve(query, config.topK(), config.weights());
        } catch (Exception e) {
            log.warn("记忆检索降级: query={}, error={}", truncate(query, 50), e.getMessage());
            return List.of();
        }
    }

    /**
     * 带请求级缓存的记忆检索 — 同一 traceId + query + topK 组合只执行一次实际检索。
     */
    private List<RetrievalResult> cachedRetrieve(String traceId, String query, RetrievalStrategyConfig config) {
        if (query == null || query.isBlank() || hybridRetriever == null) {
            return List.of();
        }
        String cacheKey = traceId + "|" + query + "|" + config.topK();
        return retrievalCache.computeIfAbsent(cacheKey, k -> {
            try {
                return hybridRetriever.retrieve(query, config.topK(), config.weights());
            } catch (Exception e) {
                log.warn("记忆检索降级: query={}, error={}", truncate(query, 50), e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * 清除指定 traceId 的检索缓存，在 AgentLoop 请求结束时调用。
     *
     * @param traceId 要清除缓存的 traceId
     */
    public void clearCache(String traceId) {
        if (traceId == null) return;
        retrievalCache.entrySet().removeIf(entry -> entry.getKey().startsWith(traceId + "|"));
    }

    /**
     * 安全执行查询改写 + 检索 — 集成 QueryRewriter 到检索流程。
     *
     * <p>rewrite 模式：原始查询和改写变体分别传入 HybridRetriever，按 entityId 去重合并（保留最高 fusedScore）。
     * hyde 模式：将 hydeEmbedding 传入向量检索（当前使用原始查询检索）。
     * none 模式 / QueryRewriter 为 null：保持现有行为。</p>
     */
    private List<RetrievalResult> safeRewriteAndRetrieve(String traceId, String refinedQuery,
                                                          RetrievalStrategyConfig config) {
        if (queryRewriter == null) {
            return cachedRetrieve(traceId, refinedQuery, config);
        }
        try {
            var rewriteResult = queryRewriter.rewrite(refinedQuery);

            // rewrite 模式：多查询检索 + 去重合并
            if (!rewriteResult.rewrittenQueries().isEmpty()) {
                // 原始查询检索
                var allResults = new ArrayList<>(cachedRetrieve(traceId, rewriteResult.primaryQuery(), config));

                // 每个改写变体检索
                for (var rewrittenQuery : rewriteResult.rewrittenQueries()) {
                    var variantResults = cachedRetrieve(traceId, rewrittenQuery, config);
                    allResults.addAll(variantResults);
                }

                // 按 entityId 去重，保留最高 fusedScore
                var deduped = new java.util.HashMap<String, RetrievalResult>();
                for (var result : allResults) {
                    deduped.merge(result.entityId(), result,
                            (existing, incoming) -> incoming.fusedScore() > existing.fusedScore() ? incoming : existing);
                }

                var merged = deduped.values().stream()
                        .sorted()
                        .limit(config.topK())
                        .toList();
                log.debug("查询改写检索: mode=rewrite, 原始结果={}, 去重后={}", allResults.size(), merged.size());
                return merged;
            }

            // hyde 模式：当前使用原始查询检索（hydeEmbedding 可用于后续向量检索优化）
            if (rewriteResult.hydeEmbedding().isPresent()) {
                log.debug("查询改写检索: mode=hyde, 使用原始查询检索");
                return cachedRetrieve(traceId, rewriteResult.primaryQuery(), config);
            }

            // none 模式：直接使用原始查询
            return cachedRetrieve(traceId, rewriteResult.primaryQuery(), config);
        } catch (Exception e) {
            log.warn("查询改写检索失败，降级为原始查询检索: error={}", e.getMessage());
            return cachedRetrieve(traceId, refinedQuery, config);
        }
    }

    /** 安全获取 HybridRetriever 最近一次 L4 意图匹配结果，异常时返回 Optional.empty()。 */
    private Optional<ReasoningSlot> safeGetLastProcedureSlot(HybridRetriever retriever) {
        try {
            return retriever.getLastProcedureSlot();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 安全获取会话槽位，异常时返回空列表。 */
    private List<WorkingMemorySlot> safeGetContext(WorkingMemory memory, String sessionId) {
        try {
            return memory.getContext(sessionId);
        } catch (Exception e) {
            log.warn("工作记忆降级: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 L1 读取当前会话对话历史，排除当前轮用户消息（避免与 state.goal() 重复）。
     *
     * <p>因为 AgentLoop 在 assembleContext() 之前已将用户消息写入 L1，
     * 而 state.goal() 会作为用户请求区域单独注入到提示词中，
     * 所以需要排除 L1 中最后一条与 currentGoal 内容相同的 USER 消息。</p>
     */
    private List<WorkingMemorySlot> safeGetSessionHistory(
            @Nullable WorkingMemory memory, String sessionId, String currentGoal) {
        if (memory == null) return List.of();
        try {
            var allSlots = memory.getContext(sessionId);
            if (allSlots == null || allSlots.isEmpty()) return List.of();
            return filterOutCurrentUserMessage(allSlots, currentGoal);
        } catch (Exception e) {
            log.warn("L1 对话历史读取失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从槽位列表中排除最后一条与 currentGoal 内容相同的 USER 类型消息。
     *
     * <p>仅匹配 {@link ConversationSlot} 类型且 role 为 "user" 的槽位，
     * 从后往前查找第一条匹配项并移除。</p>
     */
    List<WorkingMemorySlot> filterOutCurrentUserMessage(
            List<WorkingMemorySlot> slots, String currentGoal) {
        if (currentGoal == null || currentGoal.isBlank() || slots.isEmpty()) {
            return slots;
        }
        // 从后往前找到最后一条 USER 消息，如果内容与 currentGoal 相同则排除
        var result = new ArrayList<>(slots);
        for (int i = result.size() - 1; i >= 0; i--) {
            if (result.get(i) instanceof ConversationSlot cs
                    && "user".equalsIgnoreCase(cs.role())
                    && currentGoal.equals(cs.content())) {
                result.remove(i);
                break; // 只排除最后一条匹配的
            }
        }
        return List.copyOf(result);
    }

    /**
     * 安全执行 L2 跨会话检索，排除当前 sessionId，异常时返回空列表。
     *
     * <p>L2 仅负责跨会话的语义相关片段检索，当前会话历史由 L1 独占提供。</p>
     */
    private List<MessageRecord> safeSearchCrossSession(
            @Nullable EpisodicMemory episodicMemory, String query, String sessionId) {
        if (episodicMemory == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return episodicMemory.searchExcludingSession(query, sessionId, 5);
        } catch (Exception e) {
            log.warn("L2 跨会话检索降级: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 对跨会话消息列表执行语义相似度过滤（批量 embedding）。
     *
     * <p>使用 LlmRouter.embedBatch() 批量计算所有跨会话消息的 embedding，
     * 替代逐条调用消除 N+1 问题。embedBatch 异常时降级为逐条 embed() 调用。
     * embedding 服务不可用时降级跳过语义过滤。</p>
     *
     * @param fragments    跨会话消息列表
     * @param refinedQuery 精炼后的查询文本
     * @return 语义相关的消息列表
     */
    List<MessageRecord> filterBySemanticSimilarity(
            List<MessageRecord> fragments, String refinedQuery) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        if (llmRouter == null) {
            log.debug("跨会话语义过滤: LlmRouter 为 null，跳过语义过滤");
            return fragments;
        }
        float threshold = memoryProperties != null
                ? memoryProperties.getRetrieval().getMinCrossSessionSemanticScore() : 0.3f;
        try {
            float[] queryEmbedding = llmRouter.embed(refinedQuery);

            // 过滤空内容，收集有效消息和内容
            var contents = new ArrayList<String>();
            var validFragments = new ArrayList<MessageRecord>();
            for (var msg : fragments) {
                String content = msg.effectiveContent();
                if (content != null && !content.isBlank()) {
                    contents.add(content);
                    validFragments.add(msg);
                }
            }
            if (validFragments.isEmpty()) {
                log.debug("跨会话语义过滤: 所有消息内容为空，跳过");
                return List.of();
            }

            // 批量 embedding，异常时降级为逐条调用
            float[][] embeddings;
            try {
                embeddings = llmRouter.embedBatch(contents);
            } catch (Exception e) {
                log.warn("批量 embedding 失败，降级为逐条调用: error={}", e.getMessage());
                return filterBySemanticSimilarityFallback(validFragments, queryEmbedding, threshold);
            }

            // 按相似度阈值过滤
            var filtered = new ArrayList<MessageRecord>();
            for (int i = 0; i < validFragments.size(); i++) {
                if (embeddings[i] != null) {
                    float similarity = cosineSimilarity(queryEmbedding, embeddings[i]);
                    if (similarity >= threshold) {
                        filtered.add(validFragments.get(i));
                    }
                }
            }

            if (filtered.isEmpty()) {
                log.debug("跨会话语义过滤: 过滤后结果为空，跳过跨会话片段注入");
                return List.of();
            }
            log.debug("跨会话语义过滤: 原始={}, 有效={}, 过滤后={}, threshold={}",
                    fragments.size(), validFragments.size(), filtered.size(), threshold);
            return List.copyOf(filtered);
        } catch (Exception e) {
            log.warn("跨会话语义过滤降级: embedding 服务不可用, error={}", e.getMessage());
            return fragments;
        }
    }

    /**
     * 逐条 embed 降级过滤 — embedBatch 异常时的回退逻辑。
     */
    private List<MessageRecord> filterBySemanticSimilarityFallback(
            List<MessageRecord> validFragments, float[] queryEmbedding, float threshold) {
        var filtered = new ArrayList<MessageRecord>();
        for (var msg : validFragments) {
            try {
                float[] msgEmbedding = llmRouter.embed(msg.effectiveContent());
                float similarity = cosineSimilarity(queryEmbedding, msgEmbedding);
                if (similarity >= threshold) {
                    filtered.add(msg);
                }
            } catch (Exception e) {
                // 单条消息 embedding 失败时保留该消息
                filtered.add(msg);
            }
        }
        return List.copyOf(filtered);
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0.0f;
        float dotProduct = 0.0f, normA = 0.0f, normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denominator == 0.0f ? 0.0f : dotProduct / denominator;
    }

    /**
     * 检测话题是否切换 — 当前查询与上一轮查询的余弦相似度低于阈值时判定为话题切换。
     *
     * <p>话题切换时清除 {@code retrievalCache}。LlmRouter 为 null 或 embedding 失败时返回 false（不检测）。</p>
     *
     * @param refinedQuery 精炼后的查询文本
     * @return true 表示话题已切换
     */
    boolean detectTopicSwitch(String refinedQuery) {
        if (llmRouter == null || lastQueryEmbedding == null) {
            updateLastQueryEmbedding(refinedQuery);
            return false;
        }
        try {
            float[] currentEmbedding = llmRouter.embed(refinedQuery);
            float similarity = cosineSimilarity(lastQueryEmbedding, currentEmbedding);
            lastQueryEmbedding = currentEmbedding;
            float threshold = memoryProperties != null
                    ? memoryProperties.getRetrieval().getTopicSwitchThreshold() : 0.3f;
            if (similarity < threshold) {
                retrievalCache.clear();
                log.debug("话题切换检测: similarity={}, threshold={}, 已清除检索缓存", similarity, threshold);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("话题切换检测失败: error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 更新上一轮查询的 embedding 缓存。
     */
    private void updateLastQueryEmbedding(String refinedQuery) {
        if (llmRouter == null || refinedQuery == null || refinedQuery.isBlank()) return;
        try {
            lastQueryEmbedding = llmRouter.embed(refinedQuery);
        } catch (Exception ignored) {
            // embedding 失败时静默跳过
        }
    }

    /**
     * 安全 drain 被动通知队列，异常时返回空列表。
     */
    private List<String> safeDrainPassiveNotifications() {
        if (passiveNotificationQueue == null) return List.of();
        try {
            var notifications = passiveNotificationQueue.drainAll();
            if (notifications.isEmpty()) return List.of();
            return notifications.stream()
                    .map(n -> "[%s] %s (%s)".formatted(n.typeId(), n.contentJson(), n.enqueuedAt()))
                    .toList();
        } catch (Exception e) {
            log.warn("被动通知队列 drain 失败，降级跳过: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 安全查询用户画像实体，异常时返回空字符串。
     *
     * <p>从 L3 语义记忆中查询 PREFERENCE/HABIT/GOAL 三种类型的当前实体，
     * 对候选实体做关键词匹配过滤，无匹配时按 importanceScore 降序兜底，
     * 总数上限 maxUserProfileEntities。</p>
     *
     * @param semanticMemory 语义记忆（可空）
     * @param refinedQuery   精炼后的查询文本，用于关键词匹配
     * @return 格式化的用户画像文本，无数据时返回空字符串
     */
    private String safeGetUserProfile(@Nullable SemanticMemory semanticMemory, String refinedQuery) {
        if (semanticMemory == null) return "";
        try {
            // 查询 PREFERENCE/HABIT/GOAL 三种类型替代 PERSON
            var candidates = new ArrayList<TemporalEntity>();
            for (var type : List.of(EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL)) {
                candidates.addAll(semanticMemory.findCurrentByType(type));
            }
            if (candidates.isEmpty()) return "";

            // 读取配置（memoryProperties 可空时使用默认值）
            int maxEntities = memoryProperties != null
                    ? memoryProperties.getRetrieval().getMaxUserProfileEntities() : 10;
            int fallbackCount = memoryProperties != null
                    ? memoryProperties.getRetrieval().getFallbackUserProfileCount() : 3;

            // 关键词匹配过滤：将 refinedQuery 按空白分词，匹配 textRepresentation()
            List<TemporalEntity> matched = List.of();
            if (refinedQuery != null && !refinedQuery.isBlank()) {
                var keywords = List.of(refinedQuery.split("\\s+"));
                matched = candidates.stream()
                        .filter(e -> {
                            String text = e.textRepresentation().toLowerCase();
                            return keywords.stream().anyMatch(kw -> text.contains(kw.toLowerCase()));
                        })
                        .toList();
            }

            List<TemporalEntity> selected;
            if (!matched.isEmpty()) {
                // 有匹配：按 importanceScore 降序，截取上限
                selected = matched.stream()
                        .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                        .limit(maxEntities)
                        .toList();
            } else {
                // 无匹配：按 importanceScore 降序取前 fallbackCount 条兜底
                selected = candidates.stream()
                        .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                        .limit(fallbackCount)
                        .toList();
            }

            if (selected.isEmpty()) return "";
            return formatUserProfile(selected);
        } catch (Exception e) {
            log.warn("用户画像查询失败，降级跳过: error={}", e.getMessage());
            return "";
        }
    }

    /**
     * 格式化用户画像实体为结构化文本。
     *
     * <p>每个实体输出类型标签、名称、描述和属性键值对，
     * 用于注入到 User Prompt 的半稳定区。</p>
     */
    String formatUserProfile(List<TemporalEntity> entities) {
        if (entities.isEmpty()) return "";
        var sb = new StringBuilder("\n\n用户画像:\n");
        for (var entity : entities) {
            sb.append("- [").append(entity.type().label()).append("] ").append(entity.name());
            if (entity.description() != null && !entity.description().isBlank()) {
                sb.append(": ").append(entity.description());
            }
            if (!entity.properties().isEmpty()) {
                entity.properties().forEach((k, v) ->
                        sb.append("\n  ").append(k).append(": ").append(v));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化跨会话检索结果为字符串列表。
     *
     * <p>每条结果格式为 {@code [角色] 有效内容}，便于注入到用户提示词中。</p>
     */
    List<String> formatCrossSessionFragments(List<MessageRecord> fragments) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        return fragments.stream()
                .map(msg -> "[%s] %s".formatted(msg.role(), msg.effectiveContent()))
                .toList();
    }

    /** 安全执行预算分配，异常时使用静态分配降级。 */
    private BudgetAllocation safeAllocate(TokenBudgetAllocator allocator, int conversationTurns, float topScore, boolean hasMemoryData) {
        return safeAllocate(allocator, conversationTurns, topScore, hasMemoryData, 0);
    }

    /**
     * 安全执行预算分配，支持会话级 Token 窗口覆盖。
     *
     * @param sessionMaxTokens 会话配置的 maxTokens，≤ 0 时使用全局默认值
     */
    private BudgetAllocation safeAllocate(TokenBudgetAllocator allocator, int conversationTurns, float topScore, boolean hasMemoryData, int sessionMaxTokens) {
        try {
            int windowSize = sessionMaxTokens > 0 ? sessionMaxTokens : config.getContext().getMaxContextTokens();
            return allocator.allocate(windowSize, conversationTurns, topScore, hasMemoryData);
        } catch (Exception e) {
            log.warn("预算分配降级: error={}", e.getMessage());
            int total = sessionMaxTokens > 0 ? sessionMaxTokens : config.getContext().getMaxContextTokens();
            return new BudgetAllocation(
                    (int) (total * 0.02),   // userProfileBudget
                    (int) (total * 0.30),   // currentSessionBudget
                    (int) (total * 0.05),   // crossSessionBudget
                    (int) (total * 0.15),   // knowledgeEntityBudget
                    (int) (total * 0.02),   // proceduralBudget
                    (int) (total * 0.03),   // knowledgeBaseBudget
                    (int) (total * 0.10),   // systemPromptBudget
                    (int) (total * 0.15),   // userMessageBudget
                    total);
        }
    }

    /**
     * 安全更新最终注入上下文的实体 accessCount，异常时 WARN 日志不影响主流程。
     *
     * @param truncatedResults 经过预算截断后的最终检索结果
     */
    private void safeUpdateAccessCounts(List<RetrievalResult> truncatedResults) {
        if (hybridRetriever == null || truncatedResults == null || truncatedResults.isEmpty()) {
            return;
        }
        try {
            hybridRetriever.updateAccessCounts(truncatedResults);
        } catch (Exception e) {
            log.warn("accessCount 更新失败，降级跳过: count={}, error={}",
                    truncatedResults.size(), e.getMessage());
        }
    }

    // --- 截断和格式化方法 ---

    /** 按 fusedScore 降序截断检索结果到 Token 预算内。 */
    List<RetrievalResult> truncateByBudget(List<RetrievalResult> results, int tokenBudget) {
        if (results.isEmpty()) return List.of();

        // 已按 fusedScore 降序排列（RetrievalResult 实现 Comparable）
        var sorted = new ArrayList<>(results);
        sorted.sort(Comparator.naturalOrder());

        var kept = new ArrayList<RetrievalResult>();
        int usedTokens = 0;
        for (var result : sorted) {
            int tokens = estimateTokens(formatSingleResult(result));
            if (usedTokens + tokens > tokenBudget && !kept.isEmpty()) {
                break;
            }
            kept.add(result);
            usedTokens += tokens;
        }
        return List.copyOf(kept);
    }

    /** 按重要度降序截断会话槽位到 Token 预算内。 */
    List<WorkingMemorySlot> truncateSlotsByBudget(List<WorkingMemorySlot> slots, int tokenBudget) {
        if (slots.isEmpty()) return List.of();

        // 按重要度降序排列
        var sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparingDouble(WorkingMemorySlot::importance).reversed());

        var kept = new ArrayList<WorkingMemorySlot>();
        int usedTokens = 0;
        for (var slot : sorted) {
            if (usedTokens + slot.tokenCount() > tokenBudget && !kept.isEmpty()) {
                break;
            }
            kept.add(slot);
            usedTokens += slot.tokenCount();
        }
        return List.copyOf(kept);
    }

    /** 将 RetrievalResult 列表格式化为字符串列表。 */
    List<String> formatRetrievalResults(List<RetrievalResult> results) {
        return results.stream()
                .map(this::formatSingleResult)
                .toList();
    }

    /**
     * 将检索上下文与 L4 程序提示以 ReasoningSlot 的形式注入到 L1 工作记忆。
     *
     * <p>
     * - 保留原有“相关记忆”字符串拼接行为；
     * - 只在 full mode 下执行，异常时静默降级，不影响主流程。
     * </p>
     */
    /**
     * 将 L4 程序提示以 ReasoningSlot 的形式注入到 L1 工作记忆。
     *
     * <p>检索结果仅通过 User Prompt 的"相关记忆"section 单次注入，
     * 不再重复写入 L1 ReasoningSlot，避免双重注入导致 Token 膨胀。</p>
     */
    private List<WorkingMemorySlot> injectProcedureReasoningSlot(WorkingMemory memory,
                                                                 String sessionId,
                                                                 Optional<ReasoningSlot> procedureHintSlot,
                                                                 List<WorkingMemorySlot> existingSlots) {
        if (sessionId == null || sessionId.isBlank()) {
            return existingSlots;
        }
        var updated = new ArrayList<>(existingSlots != null ? existingSlots : List.of());
        try {
            // L4 程序提示 → ReasoningSlot（若尚未存在）
            if (procedureHintSlot != null && procedureHintSlot.isPresent()) {
                ReasoningSlot slot = procedureHintSlot.get();
                if (slot.thought() != null && !slot.thought().isBlank()) {
                    boolean alreadyExists = updated.stream()
                            .filter(s -> s instanceof ReasoningSlot)
                            .map(s -> (ReasoningSlot) s)
                            .anyMatch(rs -> rs.thought() != null && rs.thought().equals(slot.thought()));
                    if (!alreadyExists) {
                        memory.append(sessionId, slot);
                        updated.add(slot);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("L4 程序提示注入 L1 失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
        return List.copyOf(updated);
    }

    /**
     * 将 L4 意图匹配提示注入到“相关记忆”列表的首位，并按 tokenBudget 截断。
     *
     * <p>注入的提示属于“检索增强信息”，不占用 WorkingMemory budget。</p>
     */
    private List<String> injectProcedureHint(List<String> memories,
                                             Optional<ReasoningSlot> procedureHintSlot,
                                             int tokenBudget) {
        if (procedureHintSlot == null || procedureHintSlot.isEmpty()) {
            return memories;
        }
        var slot = procedureHintSlot.get();
        if (slot.thought() == null || slot.thought().isBlank()) {
            return memories;
        }
        var items = new ArrayList<String>();
        items.add("[PROCEDURE] " + slot.thought());
        items.addAll(memories);
        return truncateStringsByBudget(items, tokenBudget);
    }

    /** 按列表顺序截断字符串列表到 tokenBudget 内（至少保留 1 条）。 */
    private List<String> truncateStringsByBudget(List<String> items, int tokenBudget) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        var kept = new ArrayList<String>();
        int usedTokens = 0;
        for (var item : items) {
            int tokens = estimateTokens(item);
            if (usedTokens + tokens > tokenBudget && !kept.isEmpty()) {
                break;
            }
            kept.add(item);
            usedTokens += tokens;
        }
        return List.copyOf(kept);
    }

    /**
     * 基于会话关联的 knowledgeBaseIds 检索文档分块，并格式化为可注入 Prompt 的字符串列表。
     *
     * <p>依赖可选：DocumentRetriever / SessionKnowledgeBaseRepository 任一缺失时返回空列表。</p>
     */
    private List<String> safeRetrieveKnowledgeBaseSnippets(String sessionId, String query, int topK) {
        try {
            if (sessionId == null || sessionId.isBlank() || query == null || query.isBlank()) {
                return List.of();
            }
            var kbRepo = this.sessionKnowledgeBaseRepository;
            var docRetriever = this.documentRetriever;
            if (docRetriever == null || kbRepo == null) {
                return List.of();
            }
            var kbIds = kbRepo.findKnowledgeBaseIdsBySessionId(sessionId);
            if (kbIds == null || kbIds.isEmpty()) {
                return List.of();
            }

            List<DocumentSearchResult> results = docRetriever.retrieve(query, kbIds, topK);
            if (results == null || results.isEmpty()) {
                return List.of();
            }

            List<String> formatted = new ArrayList<>();
            for (var r : results) {
                String docName = r.documentId();
                if (documentRepository != null) {
                    try {
                        var docOpt = documentRepository.findById(r.documentId());
                        if (docOpt.isPresent()) {
                            docName = docOpt.get().fileName();
                        }
                    } catch (Exception ignore) {
                        // 文档名查询失败不影响主流程
                    }
                }
                String heading = "";
                if (r.headingHierarchy() != null && !r.headingHierarchy().isEmpty()) {
                    var items = r.headingHierarchy().stream()
                            .filter(s -> s != null && !s.isBlank())
                            .toList();
                    if (!items.isEmpty()) {
                        heading = " / " + String.join(" / ", items);
                    }
                }

                String prefix = "";
                if (r.contextPrefix().isPresent()) {
                    String p = r.contextPrefix().orElse("");
                    if (!p.isBlank()) {
                        prefix = p.strip() + "\n";
                    }
                }
                String content = r.content() != null ? r.content().strip() : "";
                String snippet = ("[KB] " + docName + heading + "\n" + prefix + content).strip();
                if (!snippet.isBlank()) {
                    formatted.add(snippet);
                }
            }

            // 防御性：按长度截断，避免知识库片段把 Prompt 撑爆（此处用粗略 token 预算）
            int kbBudget = Math.max(400, config.getContext().getMaxContextTokens() / 6);
            return truncateStringsByBudget(formatted, kbBudget);
        } catch (Exception e) {
            log.warn("知识库检索降级: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 格式化单条检索结果（可选脱敏）。 */
    private String formatSingleResult(RetrievalResult result) {
        var sb = new StringBuilder();
        sb.append("[").append(result.entityType()).append("] ")
                .append(result.name());
        String description = result.description();
        if (description != null && !description.isBlank()) {
            if (dataRedactor != null) {
                description = dataRedactor.redact(description);
            }
            sb.append(": ").append(description);
        }
        sb.append(" (score=").append(String.format("%.2f", result.fusedScore())).append(")");
        return sb.toString();
    }

    /** 统计 ConversationSlot 数量作为对话轮次。 */
    int countConversationTurns(List<WorkingMemorySlot> slots) {
        return (int) slots.stream()
                .filter(s -> s instanceof ConversationSlot)
                .count();
    }

    /** 估算文本 Token 数（区分中英文：中文 1 Token/字符，其他 4 字符/Token）。 */
    int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    // --- TokenBudget 构建 ---

    /** 构建 TokenBudget（ReAct 架构默认分配）。 */
    private TokenBudget buildTokenBudgetDefault(BudgetAllocation allocation,
                                                List<String> formattedMemories,
                                                List<WorkingMemorySlot> slots,
                                                String systemPrompt) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var staticBudget = TokenBudget.allocateDefault(totalTokens);

        int systemPromptUsed = estimateTokens(systemPrompt);
        int historyUsed = slots.stream().mapToInt(WorkingMemorySlot::tokenCount).sum();
        int memoryUsed = formattedMemories.stream().mapToInt(this::estimateTokens).sum();

        return new TokenBudget(
                allocation.systemPromptBudget(),
                allocation.currentSessionBudget(),
                allocation.knowledgeEntityBudget(),
                staticBudget.toolSchemaBudget(),
                staticBudget.toolResultBudget(),
                staticBudget.reservedBuffer(),
                systemPromptUsed,
                historyUsed,
                memoryUsed,
                0, 0
        );
    }

    // --- Prompt 构建 ---

    /**
     * 构建 ReAct 架构专用 System Prompt。
     *
     * <p>使用 {@code agent/react-system} 模板，包含角色定义、ReAct 循环行为指令、
     * 工具使用规范、上下文利用指南、回复风格和真实性约束。</p>
     *
     * @return System Prompt 文本
     */
    String buildReactSystemPrompt() {
        String roleDefinition = promptRegistry.render("agent/role-definition");
        String contextGuide = promptRegistry.render("agent/context-guide");
        var now = ZonedDateTime.now();
        return promptRegistry.render("agent/react-system", Map.of(
                "roleDefinition", roleDefinition,
                "contextGuide", contextGuide,
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", ZoneId.systemDefault().getId(),
                "locale", Locale.getDefault().toLanguageTag()));
    }

    /**
     * 增强系统提示词 — 追加流式约束和可选的 A2UI 提示词。
     *
     * <p>将流式输出约束（{@code agent/streaming-constraint}）和可选的 A2UI 组件目录
     * 追加到基础系统提示词之后，集中管理提示词组装逻辑。</p>
     *
     * @param baseSystemPrompt 基础系统提示词
     * @param a2uiPrompt       A2UI 组件目录提示词（可空，未启用时传 null）
     * @return 增强后的系统提示词
     */
    public String enhanceSystemPromptForStreaming(String baseSystemPrompt,
                                                  @Nullable String a2uiPrompt) {
        String streamingConstraint = promptRegistry.render("agent/streaming-constraint");
        var sb = new StringBuilder(baseSystemPrompt != null ? baseSystemPrompt : "");
        if (streamingConstraint != null && !streamingConstraint.isBlank()) {
            sb.append("\n").append(streamingConstraint);
        }
        if (a2uiPrompt != null && !a2uiPrompt.isBlank()) {
            sb.append("\n").append(a2uiPrompt);
        }
        return sb.toString();
    }

    private String safeReactSystemPrompt() {
        try {
            return buildReactSystemPrompt();
        } catch (Exception e) {
            log.error("系统提示词渲染失败，使用紧急兜底提示词: error={}", e.getMessage());
            var now = ZonedDateTime.now();
            String timeContext = "当前时间：" + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + "，时区：" + ZoneId.systemDefault().getId()
                    + "，区域：" + Locale.getDefault().toLanguageTag();
            return """
                    你是知微（ZhiWei），一个可靠、友好、谨慎的 AI 助手。
                    %s
                    请根据用户请求提供帮助。
                    """.formatted(timeContext);
        }
    }

    /**
     * 构建增强版 User Prompt（结构化内容区域）。
     *
     * <p>使用 {@code agent/react-user-prompt} 模板渲染，各区域数据预格式化后作为模板变量传入。
     * 顺序：当前时间 → 用户画像 → 被动通知 → 对话历史 → 相关记忆 → 知识库片段 → 跨会话参考
     * → 工具结果 → 推理上下文 → 当前用户请求 → 预算剩余</p>
     */
    String buildEnhancedUserPrompt(ReactAgentState state,
                                   List<String> memories,
                                   List<String> knowledgeBaseSnippets,
                                   List<String> crossSessionFragments,
                                   List<WorkingMemorySlot> slots,
                                   @Nullable String userProfile) {
        var now = ZonedDateTime.now();
        var vars = new java.util.HashMap<String, Object>();
        vars.put("currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        vars.put("timezone", ZoneId.systemDefault().getId());
        vars.put("locale", Locale.getDefault().toLanguageTag());
        vars.put("userGoal", state.goal() != null ? state.goal() : "");
        vars.put("tokensRemaining", String.valueOf(state.budget().tokensRemaining()));
        vars.put("stepCount", String.valueOf(state.stepCount()));

        // 各区域预格式化为文本块，空区域传空字符串（模板中直接拼接，空字符串不产生多余内容）
        vars.put("userProfileSection", formatUserProfileSection(userProfile));
        vars.put("passiveNotificationsSection", formatPassiveNotificationsSection());
        vars.put("conversationHistorySection", formatConversationHistorySection(slots));
        vars.put("memoriesSection", formatListSection("相关记忆", memories));
        vars.put("knowledgeBaseSection", formatListSection("知识库片段", knowledgeBaseSnippets));
        vars.put("crossSessionSection", formatListSection("跨会话参考", crossSessionFragments));
        vars.put("toolResultsSection", formatToolResultsSection(slots));
        vars.put("reasoningContextSection", formatReasoningContextSection(slots));

        return promptRegistry.render("agent/react-user-prompt", vars);
    }

    /**
     * 构建基础版 User Prompt（无记忆检索）。
     *
     * <p>使用 {@code agent/react-user-prompt-basic} 模板渲染。</p>
     *
     * @param state 当前 ReAct Agent 状态
     * @return User Prompt 文本
     */
    String buildUserPrompt(ReactAgentState state) {
        var now = ZonedDateTime.now();
        return promptRegistry.render("agent/react-user-prompt-basic", Map.of(
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", ZoneId.systemDefault().getId(),
                "locale", Locale.getDefault().toLanguageTag(),
                "userGoal", state.goal() != null ? state.goal() : "",
                "tokensRemaining", String.valueOf(state.budget().tokensRemaining()),
                "stepCount", String.valueOf(state.stepCount())));
    }

    // --- 模板区域格式化辅助方法 ---

    /** 格式化用户画像区域。 */
    private String formatUserProfileSection(@Nullable String userProfile) {
        if (userProfile == null || userProfile.isBlank()) return "";
        return "\n用户画像:\n" + userProfile;
    }

    /** 格式化被动通知区域。 */
    private String formatPassiveNotificationsSection() {
        var notifications = safeDrainPassiveNotifications();
        if (notifications.isEmpty()) return "";
        var sb = new StringBuilder("\n待处理提醒:\n");
        for (var line : notifications) {
            sb.append("  - ").append(line).append("\n");
        }
        return sb.toString();
    }

    /** 格式化对话历史区域（按 createdAt 时序）。 */
    private String formatConversationHistorySection(List<WorkingMemorySlot> slots) {
        var conversationSlots = slots.stream()
                .filter(s -> s instanceof ConversationSlot)
                .map(s -> (ConversationSlot) s)
                .sorted(Comparator.comparing(ConversationSlot::createdAt))
                .toList();
        if (conversationSlots.isEmpty()) return "";
        var sb = new StringBuilder("\n对话历史:\n");
        for (var cs : conversationSlots) {
            sb.append("  [").append(cs.role()).append("] ").append(cs.content()).append("\n");
        }
        return sb.toString();
    }

    /** 格式化通用列表区域（相关记忆 / 知识库片段 / 跨会话参考）。 */
    private String formatListSection(String title, @Nullable List<String> items) {
        if (items == null || items.isEmpty()) return "";
        var sb = new StringBuilder("\n").append(title).append(":\n");
        for (var item : items) {
            sb.append("  - ").append(item).append("\n");
        }
        return sb.toString();
    }

    /** 格式化工具结果区域。 */
    private String formatToolResultsSection(List<WorkingMemorySlot> slots) {
        var toolSlots = slots.stream()
                .filter(s -> s instanceof ToolResultSlot)
                .map(s -> (ToolResultSlot) s)
                .toList();
        if (toolSlots.isEmpty()) return "";
        var sb = new StringBuilder("\n工具结果:\n");
        for (var ts : toolSlots) {
            sb.append("  ").append(ts.toolId()).append(".").append(ts.toolAction())
                    .append(" → ").append(truncate(ts.result(), 200)).append("\n");
        }
        return sb.toString();
    }

    /** 格式化推理上下文区域（排除检索来源的 ReasoningSlot）。 */
    private String formatReasoningContextSection(List<WorkingMemorySlot> slots) {
        var reasoningSlots = slots.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .map(s -> (ReasoningSlot) s)
                .filter(rs -> !"hybrid-retrieval".equals(rs.source()))
                .toList();
        if (reasoningSlots.isEmpty()) return "";
        var sb = new StringBuilder("\n推理上下文:\n");
        for (var rs : reasoningSlots) {
            sb.append("  [").append(rs.source()).append("] ").append(rs.thought()).append("\n");
        }
        return sb.toString();
    }

    // --- 可观测性日志 ---

    /** 记录组装指标。 */
    private void logAssemblyMetrics(ReactAgentState state, AssembledContext context, Instant startTime) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();

        log.info("上下文组装完成: sessionId={}, totalTokensConsumed={}, assemblyDurationMs={}",
                state.sessionId(), context.totalTokens(), durationMs);

        if (!context.retrievedMemories().isEmpty()) {
            log.debug("检索结果详情: count={}, topScore={}, memories={}",
                    context.retrievalCount(), context.topRetrievalScore(),
                    context.retrievedMemories().stream()
                            .map(m -> truncate(m, 60))
                            .toList());
        }

        if (context.degraded()) {
            log.warn("上下文组装降级: sessionId={}", state.sessionId());
        }
    }

    // --- 工具方法 ---

    /** 截断文本到指定长度。 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
