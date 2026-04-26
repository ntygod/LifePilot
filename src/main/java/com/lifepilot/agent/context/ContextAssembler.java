package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.datastore.model.FieldHint;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.experience.SubtaskReflector;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.spec.SkillPriority;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import com.lifepilot.skill.validation.SkillRequirementGate;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 从 transcript、工作区与长期记忆中组装提示词上下文。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);
    private static final ObjectMapper SHARED_MAPPER =
            new ObjectMapper();
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual().start(command);

    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;

    /** 记忆统计缓存（不可变 record）。 */
    private record MetadataCache(MemoryCounts counts, Instant cachedAt) {}

    /**
     * 记忆统计缓存上限 — 按 filter 为 key 分桶；LRU 淘汰。
     *
     * <p>32 足以覆盖"主账户 + N 个项目 × 若干 scope 组合"常见工作集；
     * 超过后按访问顺序淘汰最旧项，控制内存占用。</p>
     */
    private static final int METADATA_CACHE_MAX_SIZE = 32;

    /**
     * 按 filter 分桶的记忆统计缓存。
     *
     * <p>为什么按 filter 分桶：{@code buildMemoryCounts(MemoryReadFilter)} 每个项目
     * 传入的 filter 不同（spaceIds 不同），若仅按时间 TTL 单桶会导致主账户缓存被
     * 当作隔离项目的返回值（跨项目污染）。 {@link MemoryReadFilter} 是 record，
     * 天然支持 equals / hashCode，可直接作为 key。</p>
     *
     * <p>使用 {@link LinkedHashMap} accessOrder 模式 + 外部同步实现 LRU；每次访问更新顺序。
     * 不用 {@link java.util.concurrent.ConcurrentHashMap} 是因为它无法原生支持 LRU 淘汰。</p>
     */
    private final Map<MemoryReadFilter, MetadataCache> metadataCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<MemoryReadFilter, MetadataCache> eldest) {
                    return size() > METADATA_CACHE_MAX_SIZE;
                }
            });

    /**
     * 记忆分类计数, 供各 context section 首行展示 —
     * 原先独立的 {@code <memory_metadata>} 标签块被拆分合并到对应 section, 标签与计数就近。
     */
    public record MemoryCounts(String profileLine, String experienceLine, String factLine) {
        public static final MemoryCounts EMPTY = new MemoryCounts("", "", "");
    }
    private static final Duration METADATA_CACHE_TTL = Duration.ofMinutes(5);

    private final AgentConfigProperties config;
    private final LocationResolver locationResolver;
    private final PromptRegistry promptRegistry;
    @Nullable private final DataRedactor dataRedactor;
    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final MemoryProperties memoryProperties;
    @Nullable private final ProceduralMemory proceduralMemory;
    @Nullable private final EffectivenessTracker effectivenessTracker;
    @Nullable private final SkillRegistry skillRegistry;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final ContextEngine contextEngine;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final SessionDatastoreRepository sessionDatastoreRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final CollectionRepository collectionRepository;
    @Nullable private final DynamicToolRegistry toolRegistry;
    @Nullable private final McpConfigProperties mcpConfig;
    @Nullable private final HybridRetriever hybridRetriever;
    @Nullable private volatile WeatherService weatherService;
    /** Skill 安装事实源 — Phase A.7 新增，driven by {@code skills} 表判断 enabled。 */
    @Nullable private volatile SkillInstallationRepository skillInstallationRepository;
    /** Skill 运行期依赖门控 — Phase A.7 新增，过滤 bins/env/os/tools 不满足的 skill。 */
    @Nullable private volatile SkillRequirementGate skillRequirementGate;
    @Nullable private volatile ProjectContextResolver projectContextResolver;
    @Nullable private volatile ChatSessionRepository chatSessionRepository;

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory, memoryProperties,
                proceduralMemory, effectivenessTracker, skillRegistry,
                null, null, null, null, null, null,
                null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory, memoryProperties,
                proceduralMemory, effectivenessTracker, skillRegistry,
                generationRouter, null, null, null, null, null,
                null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory, memoryProperties,
                proceduralMemory, effectivenessTracker, skillRegistry,
                generationRouter, contextEngine, null, null, null, null,
                null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable SessionDatastoreRepository sessionDatastoreRepository,
                            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
                            @Nullable CollectionRepository collectionRepository) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory, memoryProperties,
                proceduralMemory, effectivenessTracker, skillRegistry,
                generationRouter, contextEngine,
                sessionKnowledgeBaseRepository, sessionDatastoreRepository,
                knowledgeBaseRepository, collectionRepository,
                null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable SessionDatastoreRepository sessionDatastoreRepository,
                            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
                            @Nullable CollectionRepository collectionRepository,
                            @Nullable DynamicToolRegistry toolRegistry,
                            @Nullable McpConfigProperties mcpConfig,
                            @Nullable HybridRetriever hybridRetriever) {
        this.config = config;
        this.locationResolver = new LocationResolver(config);
        this.promptRegistry = promptRegistry;
        this.dataRedactor = dataRedactor;
        this.semanticMemory = semanticMemory;
        this.memoryProperties = memoryProperties;
        this.proceduralMemory = proceduralMemory;
        this.effectivenessTracker = effectivenessTracker;
        this.skillRegistry = skillRegistry;
        this.generationRouter = generationRouter;
        this.contextEngine = contextEngine;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.sessionDatastoreRepository = sessionDatastoreRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.collectionRepository = collectionRepository;
        this.toolRegistry = toolRegistry;
        this.mcpConfig = mcpConfig;
        this.hybridRetriever = hybridRetriever;
    }

    /** 注入天气服务（可选，由 AutoConfiguration 调用）。 */
    public void setWeatherService(@Nullable WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /** 注入 Skill 安装仓库（可选，由 AutoConfiguration 调用，Phase A.7）。 */
    public void setSkillInstallationRepository(@Nullable SkillInstallationRepository repository) {
        this.skillInstallationRepository = repository;
    }

    /** 注入 Skill 依赖门控（可选，由 AutoConfiguration 调用，Phase A.7）。 */
    public void setSkillRequirementGate(@Nullable SkillRequirementGate gate) {
        this.skillRequirementGate = gate;
    }

    /**
     * 注入项目上下文解析器（可选）。
     *
     * <p>用于在 {@link #assemble(ReactAgentState)} 入口一次性 resolve ProjectContext，
     * 供四路并行检索按项目上下文构造记忆 filter。缺失时回退 userMemory/agentExperience/userProfile 原行为。</p>
     */
    public void setProjectContextResolver(@Nullable ProjectContextResolver projectContextResolver) {
        this.projectContextResolver = projectContextResolver;
    }

    /**
     * 注入 Web 会话仓库（可选）。
     *
     * <p>用于根据 {@code state.sessionId()} 反查 projectId，再配合
     * {@link ProjectContextResolver} 得到 ProjectContext。</p>
     */
    public void setChatSessionRepository(@Nullable ChatSessionRepository chatSessionRepository) {
        this.chatSessionRepository = chatSessionRepository;
    }

    public AssembledContext assemble(ReactAgentState state) {
        Instant startTime = Instant.now();
        try {
            boolean mediaPlaceholder = isMediaPlaceholderQuery(state.goal());
            int contextWindow = resolveContextWindow(state);
            int totalContextTokens = Math.max(
                    1024,
                    contextWindow - Math.max(0, config.getContext().getOutputReservedTokens()));
            // 入口一次性 resolve ProjectContext，四路并行检索复用同一上下文
            ProjectContext projectContext = resolveProjectContext(state);
            MemoryReadFilter experienceFilter = toProjectFilter(projectContext, Set.of(MemoryScope.AGENT_EXPERIENCE));
            MemoryReadFilter userMemoryFilter = toProjectFilter(projectContext,
                    Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
            MemoryReadFilter userProfileFilter = toProjectFilter(projectContext, Set.of(MemoryScope.USER_PROFILE));
            // 四路独立检索并行化：contextSnapshot、userProfile、experiences、relevantMemories 互不依赖
            var contextFuture = CompletableFuture.supplyAsync(
                    () -> safeLoadContextSnapshot(state, totalContextTokens), VIRTUAL_EXECUTOR);
            var profileFuture = mediaPlaceholder
                    ? CompletableFuture.completedFuture("")
                    : CompletableFuture.supplyAsync(
                        () -> safeGetUserProfile(state.goal(), userProfileFilter), VIRTUAL_EXECUTOR);
            // 简单任务跳过：state.steps() 中无任何 ToolCall 时，说明还在第一轮探索阶段，
            // 经验注入提供不了价值（query 还没具体到工具维度），直接跳过节省 token。
            Set<String> recentToolIds = collectRecentToolIds(state);
            boolean skipExperiences = mediaPlaceholder || recentToolIds.isEmpty();
            var experiencesFuture = skipExperiences
                    ? CompletableFuture.completedFuture(List.<TemporalEntity>of())
                    : CompletableFuture.supplyAsync(
                        () -> safeRetrieveExperiences(state.goal(), experienceFilter, recentToolIds),
                        VIRTUAL_EXECUTOR);
            var memoryFuture = mediaPlaceholder
                    ? CompletableFuture.completedFuture(List.<TemporalEntity>of())
                    : CompletableFuture.supplyAsync(
                        () -> safeRetrieveRelevantMemories(state.goal(), userMemoryFilter), VIRTUAL_EXECUTOR);
            CompletableFuture.allOf(contextFuture, profileFuture, experiencesFuture, memoryFuture).join();

            ContextEngine.ContextSnapshot contextSnapshot = contextFuture.join();
            List<WorkspaceItem> workspaceItems = contextSnapshot.workspaceItems();
            String userProfile = profileFuture.join();
            List<TemporalEntity> experiences = experiencesFuture.join();
            List<TemporalEntity> relevantMemories = memoryFuture.join();
            List<String> injectedIds = recordExperienceInjection(state, experiences);
            String profileSection = safeRedact(formatUserProfileSection(userProfile));
            String workspaceSection = safeRedact(formatWorkspaceSection(workspaceItems));
            String artifactSection = safeRedact(contextSnapshot.artifactSection());
            String experienceSection = safeRedact(formatExperienceSection(experiences));
            String memorySection = safeRedact(formatMemorySection(relevantMemories));

            String systemPrompt = buildAugmentedSystemPrompt(state);
            MemoryCounts memoryCounts = buildMemoryCounts(userMemoryFilter);
            List<Message> contextMessages = buildContextMessages(
                    profileSection,
                    workspaceSection,
                    artifactSection,
                    experienceSection,
                    memorySection,
                    memoryCounts
            );
            String userPrompt = buildUserPrompt(state);

            TokenBudget tokenBudget = buildTokenBudget(
                    totalContextTokens,
                    systemPrompt,
                    contextSnapshot.historyTokens(),
                    estimateContextMessageTokens(contextMessages),
                    contextSnapshot.toolResultTokens()
            );

            int workspaceTokens = estimateTokens(workspaceSection);
            AssembledContext context = new AssembledContext(
                    systemPrompt,
                    contextMessages,
                    contextSnapshot.historyMessages(),
                    userPrompt,
                    List.of(),
                    tokenBudget,
                    0,
                    0.0f,
                    workspaceTokens,
                    false,
                    injectedIds,
                    null
            );

            recordContextReport(state, contextSnapshot, context, contextWindow);
            logAssemblyMetrics(state, context, startTime);
            return context;
        } catch (Exception e) {
            log.warn("上下文组装失败，回退到最小提示词: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return buildFallbackContext(state);
        }
    }

    /**
     * 从 {@code state.sessionId()} 反查 ChatSession 的 projectId，经 resolver 得到 ProjectContext。
     *
     * <p>依赖任一缺失（resolver / chatSessionRepo 为 null）或查询/解析抛错时返回 null，
     * 调用方需在 {@link #toProjectFilter(ProjectContext, Set)} 中按 null 走 fallback 分支。</p>
     */
    @Nullable
    ProjectContext resolveProjectContext(ReactAgentState state) {
        if (projectContextResolver == null || chatSessionRepository == null) {
            return null;
        }
        String sessionId = state.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var session = chatSessionRepository.findById(sessionId);
            if (session.isEmpty()) {
                return null;
            }
            return projectContextResolver.resolve(session.get().projectId());
        } catch (Exception e) {
            log.debug("解析 ProjectContext 失败, 回退到默认 filter: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 按 ProjectContext + scopes 构造记忆读取 filter。
     *
     * <p>ctx 为 null 时按 scopes 回退到 {@link MemoryReadFilter#userProfile()} /
     * {@link MemoryReadFilter#userMemory()} / {@link MemoryReadFilter#agentExperience()} 原行为，
     * 保证新旧路径向后兼容。</p>
     *
     * <p>仅为保留调用点可读性；实际逻辑 delegate 到
     * {@link MemoryReadFilter#fromProjectContextOrFallback}。</p>
     */
    MemoryReadFilter toProjectFilter(@Nullable ProjectContext ctx, Set<MemoryScope> scopes) {
        return MemoryReadFilter.fromProjectContextOrFallback(
                ctx != null,
                ctx != null ? ctx.projectSpaceId() : null,
                ctx != null ? ctx.personalSpaceId() : null,
                ctx != null ? ctx.experienceSpaceId() : null,
                ctx != null && ctx.isolated(),
                scopes);
    }

    private AssembledContext buildFallbackContext(ReactAgentState state) {
        String systemPrompt = buildAugmentedSystemPrompt(state);
        String userPrompt = buildUserPrompt(state);
        TokenBudget budget = buildTokenBudget(
                resolveContextBudgetTokens(state),
                systemPrompt,
                0,
                0,
                0);
        return new AssembledContext(
                systemPrompt,
                List.of(),
                List.of(),
                userPrompt,
                List.of(),
                budget,
                0,
                0.0f,
                0,
                true,
                List.of(),
                null
        );
    }

    /** 提取 state.steps() 中已经使用过的工具 ID 集合，用于经验检索的工具维度加权与门槛判断。 */
    static Set<String> collectRecentToolIds(@Nullable ReactAgentState state) {
        if (state == null || state.steps() == null) {
            return Set.of();
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (var step : state.steps()) {
            if (step instanceof ReactStep.ToolCall call && call.toolId() != null && !call.toolId().isBlank()) {
                ids.add(call.toolId());
            }
        }
        return Set.copyOf(ids);
    }

    boolean isMediaPlaceholderQuery(@Nullable String goal) {
        if (goal == null || goal.isBlank()) {
            return true;
        }
        String trimmed = goal.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() <= 20;
    }

    /**
     * 经验检索 —— 用 {@link HybridRetriever} 按 query 做语义检索（embedding + BM25 + 关键词三路）。
     *
     * <p>额外按 {@code recentToolIds} 做"工具维度加权"：
     * 当前任务 transcript 中已使用过的工具与经验 metadata.toolsUsed 有交集时，
     * fusedScore 加 0.2 boost，让"用同一工具的经验"优先注入。</p>
     */
    List<TemporalEntity> safeRetrieveExperiences(@Nullable String query,
                                                  MemoryReadFilter filter,
                                                  Set<String> recentToolIds) {
        if (semanticMemory == null || memoryProperties == null || hybridRetriever == null) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            MemoryProperties.Experience experience = memoryProperties.getExperience();
            if (!experience.isEnabled()) {
                return List.of();
            }

            int maxInjection = experience.getMaxInjectionCount();
            // candidateCount 取较大值（10x）：hybridRetriever 不按 entityType 过滤，
            // 候选里混合 FACT/PREFERENCE 等其他类型；候选数太小时 EXPERIENCE 可能整体被挤出。
            int candidateCount = Math.max(maxInjection * 10, 20);
            List<RetrievalResult> ranked = hybridRetriever.retrieve(
                    query, candidateCount, RetrievalWeights.DEFAULT, filter);
            // 仅保留 EXPERIENCE 类型（hybridRetriever 不限定 entityType，需后过滤）
            ranked = ranked.stream()
                    .filter(r -> EntityType.EXPERIENCE.name().equals(r.entityType()))
                    .toList();
            if (ranked.isEmpty()) {
                return List.of();
            }

            Set<String> ids = ranked.stream().map(RetrievalResult::entityId).collect(Collectors.toSet());
            Map<String, TemporalEntity> entityMap = semanticMemory.findByIds(ids, filter);
            // 保留 ranked 顺序 + 应用 isolation / granularity 过滤 + 工具维度加权
            return ranked.stream()
                    .map(r -> {
                        TemporalEntity entity = entityMap.get(r.entityId());
                        if (entity == null) return null;
                        float boostedScore = r.fusedScore() + toolWeightBoost(entity, recentToolIds);
                        return Map.entry(entity, boostedScore);
                    })
                    .filter(Objects::nonNull)
                    .filter(e -> passesIsolation(e.getKey(), experience))
                    .filter(e -> notToolLevelGranularity(e.getKey()))
                    .sorted(Map.Entry.<TemporalEntity, Float>comparingByValue().reversed())
                    .limit(maxInjection)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception e) {
            log.debug("经验检索已跳过: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 经验的 toolsUsed 与当前任务工具集有交集时加 0.2 score；无交集时不加分。 */
    @SuppressWarnings("unchecked")
    private float toolWeightBoost(TemporalEntity entity, Set<String> recentToolIds) {
        if (recentToolIds == null || recentToolIds.isEmpty()) {
            return 0.0f;
        }
        Object toolsUsed = entity.properties().get("toolsUsed");
        if (!(toolsUsed instanceof List<?> list) || list.isEmpty()) {
            return 0.0f;
        }
        for (Object t : list) {
            if (t != null && recentToolIds.contains(t.toString())) {
                return 0.2f;
            }
        }
        return 0.0f;
    }

    private boolean passesIsolation(TemporalEntity entity, MemoryProperties.Experience experience) {
        if (experience.getIsolation().isCrossContextRetrieval()) {
            return true;
        }
        Object executionContext = entity.properties().get("executionContext");
        return executionContext == null || "MAIN_AGENT".equals(executionContext.toString());
    }

    private boolean notToolLevelGranularity(TemporalEntity entity) {
        Object granularity = entity.properties().get("granularity");
        return granularity == null || !SubtaskReflector.TOOL_LEVEL.equals(granularity.toString());
    }

    String formatExperienceSection(List<TemporalEntity> experiences) {
        if (experiences == null || experiences.isEmpty() || memoryProperties == null) {
            return "";
        }

        int tokenBudget = memoryProperties.getExperience().getInjectionTokenBudget();
        StringBuilder sb = new StringBuilder("\n相关经验:\n");
        int usedTokens = 0;

        for (TemporalEntity experience : experiences) {
            String entry = "- " + experience.name() + ": "
                    + (experience.description() != null ? experience.description() : "") + "\n";
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }
            sb.append(entry);
            usedTokens += entryTokens;
        }

        return sb.toString();
    }

    /**
     * 检索与当前查询相关的记忆实体 — 通过 HybridRetriever 三路混合检索，
     * 排除已由 userProfile 和 experience 路径覆盖的类型。
     */
    List<TemporalEntity> safeRetrieveRelevantMemories(@Nullable String query) {
        return safeRetrieveRelevantMemories(query, MemoryReadFilter.userMemory());
    }

    List<TemporalEntity> safeRetrieveRelevantMemories(@Nullable String query, MemoryReadFilter filter) {
        if (hybridRetriever == null || semanticMemory == null || memoryProperties == null) {
            return List.of();
        }
        var retrieval = memoryProperties.getRetrieval();
        if (!retrieval.isMemoryContextEnabled()) {
            return List.of();
        }
        try {
            int maxEntities = retrieval.getMemoryContextMaxEntities();
            float threshold = retrieval.getMemoryContextScoreThreshold();
            // 用 HybridRetriever 检索全类型实体（userMemory scope）
            List<RetrievalResult> results = hybridRetriever.retrieve(
                    query != null ? query : "", maxEntities * 2,
                    RetrievalWeights.DEFAULT, filter);
            // 过滤已由 userProfile 和 experience 路径覆盖的类型（entityType 是 String）
            Set<String> excludedTypeNames = Set.of(
                    EntityType.PREFERENCE.name(), EntityType.HABIT.name(),
                    EntityType.GOAL.name(), EntityType.EXPERIENCE.name());
            results = results.stream()
                    .filter(r -> !excludedTypeNames.contains(r.entityType()))
                    .filter(r -> r.fusedScore() >= threshold)
                    .limit(maxEntities)
                    .toList();
            if (results.isEmpty()) {
                return List.of();
            }
            // 批量加载完整实体
            Set<String> ids = results.stream().map(RetrievalResult::entityId).collect(Collectors.toSet());
            Map<String, TemporalEntity> entityMap = semanticMemory.findByIds(ids, filter);
            return results.stream()
                    .map(r -> entityMap.get(r.entityId()))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.debug("相关记忆检索已跳过: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 格式化记忆实体为注入段 — 按 token 预算截断，避免超出上下文窗口。
     */
    String formatMemorySection(List<TemporalEntity> memories) {
        if (memories == null || memories.isEmpty() || memoryProperties == null) {
            return "";
        }
        int tokenBudget = memoryProperties.getRetrieval().getMemoryContextTokenBudget();
        StringBuilder sb = new StringBuilder("\n与当前话题相关的记忆:\n");
        int usedTokens = 0;
        for (TemporalEntity entity : memories) {
            String entry = "- [" + entity.type().label() + "] " + entity.name()
                    + (entity.description() != null ? ": " + entity.description() : "") + "\n";
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > tokenBudget) {
                break;
            }
            sb.append(entry);
            usedTokens += entryTokens;
        }
        return sb.toString();
    }

    String buildReactSystemPrompt() {
        return buildReactSystemPrompt(null);
    }

    String buildReactSystemPrompt(@Nullable ReactAgentState state) {
        String roleDefinition = promptRegistry.render("agent/role-definition");
        ZonedDateTime now = ZonedDateTime.now();
        String taskMode = resolveTaskMode(state);
        String systemPrompt;
        if (isTaskMode(state)) {
            systemPrompt = promptRegistry.render("agent/react-system-task", Map.of(
                    "roleDefinition", roleDefinition,
                    "taskMode", taskMode,
                    "modeSpecificRules", buildModeSpecificRules(taskMode),
                    "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timezone", ZoneId.systemDefault().getId(),
                    "locale", Locale.getDefault().toLanguageTag()
            ));
        } else {
            String contextGuide = promptRegistry.render("agent/context-guide");
            systemPrompt = promptRegistry.render("agent/react-system", Map.of(
                    "roleDefinition", roleDefinition,
                    "contextGuide", contextGuide,
                    "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timezone", ZoneId.systemDefault().getId(),
                    "locale", Locale.getDefault().toLanguageTag()
            ));
        }

        String skillCatalog = buildSkillCatalog(state);
        if (!skillCatalog.isBlank()) {
            systemPrompt = systemPrompt + "\n" + skillCatalog;
        }
        String mcpCatalog = buildMcpServerCatalog();
        if (!mcpCatalog.isBlank()) {
            systemPrompt = systemPrompt + "\n" + mcpCatalog;
        }

        return systemPrompt;
    }

    public String enhanceSystemPromptForStreaming(String baseSystemPrompt) {
        String streamingConstraint = promptRegistry.render("agent/streaming-constraint");
        StringBuilder sb = new StringBuilder(baseSystemPrompt != null ? baseSystemPrompt : "");
        if (streamingConstraint != null && !streamingConstraint.isBlank()) {
            sb.append("\n").append(streamingConstraint);
        }
        return sb.toString();
    }

    /**
     * 构建记忆分类计数 — 按实体类型分组统计, 供各 context section 首行展示。
     * 使用 SQL GROUP BY 聚合避免全量加载实体, 结果缓存 5 分钟。
     */
    MemoryCounts buildMemoryCounts() {
        return buildMemoryCounts(MemoryReadFilter.userMemory());
    }

    MemoryCounts buildMemoryCounts(MemoryReadFilter filter) {
        if (semanticMemory == null) {
            return MemoryCounts.EMPTY;
        }
        Instant now = Instant.now();
        MetadataCache cached = metadataCache.get(filter);
        if (cached != null && Duration.between(cached.cachedAt(), now).compareTo(METADATA_CACHE_TTL) < 0) {
            return cached.counts();
        }
        try {
            Map<EntityType, Integer> counts = semanticMemory.countByEntityType(filter);
            if (counts.isEmpty()) {
                metadataCache.put(filter, new MetadataCache(MemoryCounts.EMPTY, now));
                return MemoryCounts.EMPTY;
            }
            // 分类统计
            int profileCount = 0;
            int experienceCount = 0;
            int factCount = 0;
            var profileDetails = new StringBuilder();
            var factDetails = new StringBuilder();
            for (var entry : counts.entrySet()) {
                int count = entry.getValue();
                switch (entry.getKey()) {
                    case PREFERENCE, HABIT, GOAL -> {
                        profileCount += count;
                        if (!profileDetails.isEmpty()) profileDetails.append("、");
                        profileDetails.append(entry.getKey().label()).append(" ").append(count);
                    }
                    case EXPERIENCE -> experienceCount = count;
                    default -> {
                        factCount += count;
                        if (!factDetails.isEmpty()) factDetails.append("、");
                        factDetails.append(entry.getKey().label()).append(" ").append(count);
                    }
                }
            }
            String profileLine = profileCount > 0
                    ? "共 " + profileCount + " 条（" + profileDetails + "）"
                    : "";
            String experienceLine = experienceCount > 0
                    ? "共 " + experienceCount + " 条执行经验"
                    : "";
            String factLine = factCount > 0
                    ? "共 " + factCount + " 条（" + factDetails + "）"
                    : "";
            MemoryCounts result = new MemoryCounts(profileLine, experienceLine, factLine);
            metadataCache.put(filter, new MetadataCache(result, now));
            return result;
        } catch (Exception e) {
            log.debug("记忆统计构建失败: {}", e.getMessage());
            return MemoryCounts.EMPTY;
        }
    }

    String buildAugmentedSystemPrompt(ReactAgentState state) {
        // 工具使用规则集中在 react-system.st 的 <tool_protocol>；
        // 记忆工具的使用建议由 context-guide.st 提供；不再在运行时额外拼接 toolGuide / categoryHint。
        return joinNonBlankSections(
                safeReactSystemPrompt(state),
                buildExecutionGuardPrompt(state)
        );
    }

    String buildUserPrompt(ReactAgentState state) {
        ZonedDateTime now = ZonedDateTime.now();
        String weatherSuffix = "";
        if (weatherService != null) {
            try {
                String summary = weatherService.getWeatherSummary();
                if (summary != null && !summary.isBlank()) {
                    weatherSuffix = "\n- 天气: " + summary;
                }
            } catch (Exception e) {
                // 天气获取失败不影响对话
            }
        }
        String userPrompt = promptRegistry.render("agent/react-user-prompt", Map.of(
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", now.getZone().getId(),
                "location", locationResolver.resolve(),
                "osName", System.getProperty("os.name", "unknown"),
                "osVersion", System.getProperty("os.version", "unknown"),
                "channel", state.channel() != null ? state.channel() : "unknown",
                "weather", weatherSuffix,
                "userGoal", state.goal() != null ? state.goal() : ""
        ));
        // 已加载的 Skill 指南注入到 userPrompt 头部（紧邻 runtime_context 上方）
        String loadedSkills = buildLoadedSkillsSection(state);
        if (!loadedSkills.isBlank()) {
            userPrompt = loadedSkills + "\n\n" + userPrompt;
        }
        String knowledgeBindingPrompt = buildKnowledgeBindingPrompt(state.sessionId());
        if (!knowledgeBindingPrompt.isBlank()) {
            userPrompt = userPrompt + "\n\n" + knowledgeBindingPrompt;
        }
        if (state.goal() != null && state.goal().contains("<resume_user_input>")) {
            userPrompt = userPrompt + """

                    <resume_instruction>
                - 当前请求包含 <resume_user_input>，表示这是同一轮任务的补充信息，不是新的独立任务
                - 继续沿用已有进度处理，并优先利用这段补充信息解决挂起点
                </resume_instruction>
                """;
        }
        return userPrompt;
    }

    private String buildKnowledgeBindingPrompt(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }

        List<String> knowledgeBaseLines = new ArrayList<>();
        if (sessionKnowledgeBaseRepository != null) {
            for (String knowledgeBaseId : sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId)) {
                knowledgeBaseLines.add(formatKnowledgeBaseBinding(knowledgeBaseId));
            }
        }

        List<String> datastoreLines = new ArrayList<>();
        if (sessionDatastoreRepository != null) {
            for (String datastoreId : sessionDatastoreRepository.findDatastoreIdsBySessionId(sessionId)) {
                datastoreLines.add(formatDatastoreBinding(datastoreId));
            }
        }

        if (knowledgeBaseLines.isEmpty() && datastoreLines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("<active_knowledge_bindings>\n");
        if (!datastoreLines.isEmpty()) {
            sb.append("- 当前会话已绑定 Datastore：\n");
            datastoreLines.forEach(line -> sb.append("  · ").append(line).append('\n'));
        }
        if (!knowledgeBaseLines.isEmpty()) {
            sb.append("- 当前会话已绑定 Knowledge Base：\n");
            knowledgeBaseLines.forEach(line -> sb.append("  · ").append(line).append('\n'));
        }
        sb.append("</active_knowledge_bindings>");
        return sb.toString();
    }

    private String formatKnowledgeBaseBinding(String knowledgeBaseId) {
        if (knowledgeBaseRepository == null) {
            return knowledgeBaseId;
        }
        return knowledgeBaseRepository.findById(knowledgeBaseId)
                .map(knowledgeBase -> "%s (%s)".formatted(knowledgeBase.name(), knowledgeBase.id()))
                .orElse(knowledgeBaseId);
    }

    private String formatDatastoreBinding(String datastoreId) {
        if (collectionRepository == null) {
            return datastoreId;
        }
        return collectionRepository.findById(datastoreId)
                .map(this::buildDatastoreBindingSummary)
                .orElse(datastoreId);
    }

    /**
     * 构建 Datastore 绑定摘要 — 包含集合类型、描述和字段定义，
     * 让 Agent 能精准判断该用结构化查询还是语义检索。
     */
    private String buildDatastoreBindingSummary(com.lifepilot.datastore.model.Collection collection) {
        var sb = new StringBuilder();
        sb.append("%s [%s] (%s)".formatted(collection.name(), collection.timeSeries() ? "TIME_SERIES" : "GENERAL", collection.id()));
        if (collection.description() != null && !collection.description().isBlank()) {
            sb.append(" — ").append(collection.description());
        }

        // 解析并追加字段提示，让 Agent 知道可以按哪些字段做结构化查询
        if (collection.fieldHintsJson() != null && !collection.fieldHintsJson().isBlank()
                && !"[]".equals(collection.fieldHintsJson().strip())) {
            try {
                var hints = SHARED_MAPPER.readValue(
                        collection.fieldHintsJson(),
                        new TypeReference<List<FieldHint>>() {});
                if (!hints.isEmpty()) {
                    String fieldList = hints.stream()
                            .map(h -> "%s(%s)".formatted(h.name(), h.type()))
                            .collect(java.util.stream.Collectors.joining(", "));
                    sb.append("\n    字段: ").append(fieldList);
                }
            } catch (Exception e) {
                log.debug("Datastore 字段提示解析失败，跳过字段摘要: collectionId={}, error={}",
                        collection.id(), e.getMessage());
            }
        }

        // 追加检索提示
        sb.append("\n    检索: 精确字段查询用 datastore(action=query)，主题/语义检索用 knowledge.search(datastoreId=%s)"
                .formatted(collection.id()));

        return sb.toString();
    }


    List<Message> buildContextMessages(@Nullable String profileSection,
                                       @Nullable String workspaceSection,
                                       @Nullable String artifactSection,
                                       @Nullable String experienceSection,
                                       @Nullable String memorySection,
                                       MemoryCounts counts) {
        List<Message> messages = new ArrayList<>();
        // 把计数作为各 section 首行注入, 标签与数据就近
        addTaggedContextMessage(messages, "user_profile_context",
                prependCountLine(counts.profileLine(), profileSection));
        addTaggedContextMessage(messages, "workspace_context", workspaceSection);
        addTaggedContextMessage(messages, "artifact_context", artifactSection);
        addTaggedContextMessage(messages, "experience_context",
                prependCountLine(counts.experienceLine(), experienceSection));
        addTaggedContextMessage(messages, "memory_context",
                prependCountLine(counts.factLine(), memorySection));
        return List.copyOf(messages);
    }

    /** 在 section 内容前拼一行计数; 计数空或 section 空时原样返回 (避免制造空 section)。 */
    @Nullable
    private String prependCountLine(String countLine, @Nullable String section) {
        if (section == null || section.isBlank()) {
            return section;
        }
        if (countLine == null || countLine.isBlank()) {
            return section;
        }
        return countLine + "\n" + section;
    }

    private void addTaggedContextMessage(List<Message> messages,
                                         String contextType,
                                         @Nullable String sectionContent) {
        if (sectionContent == null || sectionContent.isBlank()) {
            return;
        }
        messages.add(new AssistantMessage("""
                <%s>
                %s
                </%s>
                """.formatted(contextType, sectionContent.strip(), contextType).strip()));
    }

    private String joinNonBlankSections(String... sections) {
        StringBuilder sb = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(section.strip());
        }
        return sb.toString();
    }

    private String resolveTaskMode(@Nullable ReactAgentState state) {
        if (state == null) {
            return "interactive";
        }
        if (state.sourceKind() == SourceKind.CRON) {
            return "cron";
        }
        if (state.sourceKind() == SourceKind.HEARTBEAT) {
            return "heartbeat";
        }
        return "interactive";
    }

    private boolean isTaskMode(@Nullable ReactAgentState state) {
        String mode = resolveTaskMode(state);
        return "cron".equals(mode) || "heartbeat".equals(mode);
    }

    private String buildModeSpecificRules(String taskMode) {
        return switch (taskMode) {
            case "cron" -> """
                    - 这是明确的 cron 调度任务，按计划执行，不要改写成普通对话
                    - 除非缺少执行前提，否则不要反问，不要重复任务描述
                    - 没有新的有效结果时返回 TASK_SILENT
                    """.trim();
            case "heartbeat" -> """
                    - 这是系统内部的 heartbeat 唤醒任务，不维护 checklist，也不要输出 HEARTBEAT_OK
                    - 仅在存在明确需要上报的内部结果时才返回正文
                    - 不要擅自把唤醒任务改成新的 cron 调度
                    """.trim();
            default -> """
                    - 明确时间点或周期任务时，使用 cron
                    - 模糊持续关注类请求时，不要创建过时的 heartbeat checklist；优先记录到记忆或工作区，交由主动提醒引擎后续判断
                    - 一次性分析或执行任务时，直接执行，不创建长期任务
                    """.trim();
        };
    }

    private String buildExecutionGuardPrompt(ReactAgentState state) {
        if (isTaskMode(state)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                <completion_contract>
                - 自行判断当前请求是普通问答还是多步任务
                - 普通问答/解释/分析/总结：直接回答并结束
                - 多步任务：首次调工具前用 1-3 句话简述执行计划（目标拆解 + 步骤顺序）；有必要步骤未完成时继续调用工具，不要用阶段性总结结束本轮
                - 可恢复阻塞（缺用户补充信息/等待确认/外部回传）不要包装成失败或完成
                - 需要用户补充信息时，把追问包在 <await_user_input>...</await_user_input> 中，说清：缺什么、为什么缺、补充后会继续做什么
                - 这轮没调用工具但已能给出终态时，正文后追加隐藏标签：
                  · `<completion_control>done</completion_control>` — 任务已完成
                  · `<completion_control>blocked</completion_control>` — 任务明确阻塞
                  · `<completion_control>continue</completion_control>` — 阶段说明，非终态
                - `completion_control` 仅供系统判定，不展示给用户
                - 用户明确要求结束且目标已满足时，直接自然收尾
                """);
        if (state.earlyStopRejectCount() > 0) {
            sb.append("- 系统已经拒绝过你的一次疑似提前结束；如果这轮要结束，请补上正确的 `<completion_control>` 标签；如果任务还没做完，就继续调用工具\n");
        }
        sb.append("</completion_contract>");
        return sb.toString();
    }

    private ContextEngine.ContextSnapshot safeLoadContextSnapshot(ReactAgentState state, int totalContextTokens) {
        if (contextEngine == null) {
            throw new IllegalStateException("ContextEngine 未注入");
        }
        try {
            return contextEngine.load(state, totalContextTokens);
        } catch (Exception e) {
            log.warn("ContextEngine 加载失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            throw e;
        }
    }

    private void recordContextReport(ReactAgentState state,
                                     ContextEngine.ContextSnapshot snapshot,
                                     AssembledContext context,
                                     int contextWindow) {
        if (contextEngine == null) {
            return;
        }
        contextEngine.recordReport(
                state,
                snapshot,
                context,
                contextWindow,
                Math.max(0, config.getContext().getOutputReservedTokens())
        );
    }

    /** 巩固画像实体名 — 与 UserProfileConsolidator 约定。 */
    private static final String CONSOLIDATED_PROFILE_NAME = "__consolidated_profile";

    private String safeGetUserProfile(@Nullable String refinedQuery) {
        return safeGetUserProfile(refinedQuery, MemoryReadFilter.userProfile());
    }

    private String safeGetUserProfile(@Nullable String refinedQuery, MemoryReadFilter profileFilter) {
        if (semanticMemory == null) {
            return "";
        }
        try {
            // 优先读巩固后的连贯画像（由 UserProfileConsolidator 定时生成）
            var consolidated = semanticMemory.findCurrentByNameAndType(
                    CONSOLIDATED_PROFILE_NAME, EntityType.CUSTOM, profileFilter);
            if (consolidated.isPresent()) {
                var desc = consolidated.get().description();
                if (desc != null && !desc.isBlank()) {
                    // section 标签已由外层 <user_profile_context> 提供, 不再在内容前重复 "用户画像:"
                    return desc;
                }
            }

            // 降级：碎片实体拼接
            List<TemporalEntity> candidates = new ArrayList<>();
            for (EntityType type : List.of(EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL)) {
                candidates.addAll(semanticMemory.findCurrentByType(type, profileFilter));
            }
            if (candidates.isEmpty()) {
                return "";
            }

            int maxEntities = memoryProperties != null
                    ? memoryProperties.getRetrieval().getMaxUserProfileEntities()
                    : 10;
            int fallbackCount = memoryProperties != null
                    ? memoryProperties.getRetrieval().getFallbackUserProfileCount()
                    : 3;

            List<TemporalEntity> matched = List.of();
            if (refinedQuery != null && !refinedQuery.isBlank()) {
                List<String> keywords = List.of(refinedQuery.split("\\s+"));
                matched = candidates.stream()
                        .filter(entity -> {
                            String text = entity.textRepresentation().toLowerCase(Locale.ROOT);
                            return keywords.stream()
                                    .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                                    .anyMatch(text::contains);
                        })
                        .toList();
            }

            List<TemporalEntity> selected = !matched.isEmpty()
                    ? matched.stream()
                            .sorted(Comparator.comparingDouble(
                                    (TemporalEntity e) -> computeInjectionScore(e, true)).reversed())
                            .limit(maxEntities)
                            .toList()
                    : candidates.stream()
                            .sorted(Comparator.comparingDouble(
                                    (TemporalEntity e) -> computeInjectionScore(e, false)).reversed())
                            .limit(fallbackCount)
                            .toList();

            List<PreferenceRule> highConfidencePreferences = List.of();
            if (proceduralMemory != null) {
                try {
                    highConfidencePreferences = proceduralMemory.getPreferences("user-preference").stream()
                            .filter(PreferenceRule::isHighConfidence)
                            .toList();
                } catch (Exception e) {
                    log.warn("加载 L4 偏好规则失败: error={}", e.getMessage());
                }
            }

            if (!highConfidencePreferences.isEmpty()) {
                Set<String> l4Keys = highConfidencePreferences.stream()
                        .map(PreferenceRule::key)
                        .collect(Collectors.toSet());
                selected = selected.stream()
                        .filter(entity -> !(entity.type() == EntityType.PREFERENCE
                                && l4Keys.contains(entity.name())))
                        .toList();
            }

            if (selected.isEmpty() && highConfidencePreferences.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            if (!selected.isEmpty()) {
                sb.append("L3 用户画像:\n");
                for (TemporalEntity entity : selected) {
                    sb.append("- [")
                            .append(entity.type().label())
                            .append("] ")
                            .append(entity.name());
                    if (entity.description() != null && !entity.description().isBlank()) {
                        sb.append(": ").append(entity.description());
                    }
                    sb.append('\n');
                }
            }

            if (!highConfidencePreferences.isEmpty()) {
                sb.append("L4 偏好规则:\n");
                for (PreferenceRule rule : highConfidencePreferences) {
                    sb.append("- ")
                            .append(rule.key())
                            .append(" = ")
                            .append(rule.value())
                            .append(" (confidence=")
                            .append(rule.confidence())
                            .append(")\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("加载用户画像失败: error={}", e.getMessage());
            return "";
        }
    }

    private List<String> recordExperienceInjection(ReactAgentState state, List<TemporalEntity> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return List.of();
        }
        List<String> ids = experiences.stream().map(TemporalEntity::id).toList();
        if (effectivenessTracker != null) {
            try {
                effectivenessTracker.recordInjection(state.traceId(), ids);
            } catch (Exception e) {
                log.warn("记录经验注入失败: error={}", e.getMessage());
            }
        }
        return ids;
    }

    private TokenBudget buildTokenBudget(int totalTokens,
                                         String systemPrompt,
                                         int historyTokens,
                                         int contextMessageTokens,
                                         int toolResultTokens) {
        TokenBudget base = TokenBudget.allocateDefault(totalTokens, config.getContext().getTokenAllocation());
        int systemPromptUsed = Math.max(0, estimateTokens(systemPrompt));
        int historyUsed = historyTokens;
        int memoryUsed = Math.max(0, contextMessageTokens);
        int toolResultUsed = toolResultTokens;

        return new TokenBudget(
                base.systemPromptBudget(),
                base.historyBudget(),
                base.memoryBudget(),
                base.toolSchemaBudget(),
                base.toolResultBudget(),
                base.reservedBuffer(),
                systemPromptUsed,
                historyUsed,
                memoryUsed,
                0,
                toolResultUsed
        );
    }

    private int estimateContextMessageTokens(List<Message> contextMessages) {
        if (contextMessages == null || contextMessages.isEmpty()) {
            return 0;
        }
        return contextMessages.stream()
                .mapToInt(message -> estimateTokens(message.getText()))
                .sum();
    }

    private int resolveContextBudgetTokens(@Nullable ReactAgentState state) {
        int contextWindow = resolveContextWindow(state);
        int reserved = Math.max(0, config.getContext().getOutputReservedTokens());
        return Math.max(1024, contextWindow - reserved);
    }

    private int resolveContextWindow(@Nullable ReactAgentState state) {
        int configuredWindow = Math.max(1024, config.getContext().getMaxContextTokens());
        int resolvedWindow = configuredWindow;
        if (generationRouter != null) {
            try {
                int providerWindow = generationRouter.resolveMaxContextWindow(
                        config.getLoop().getLlmScene(),
                        state != null ? state.preferredProvider() : null,
                        null);
                if (providerWindow > 0) {
                    resolvedWindow = Math.min(configuredWindow, providerWindow);
                }
            } catch (Exception e) {
                log.debug("读取 Provider 上下文窗口失败，回退默认配置: error={}", e.getMessage());
            }
        }
        return resolvedWindow;
    }

    private String formatUserProfileSection(@Nullable String userProfile) {
        if (userProfile == null || userProfile.isBlank()) {
            return "";
        }
        // section 标签由 <user_profile_context> 提供, 不再在此重复加 "用户画像:" 前缀
        return userProfile;
    }

    private String formatWorkspaceSection(List<WorkspaceItem> workspaceItems) {
        if (workspaceItems == null || workspaceItems.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n活跃工作区:\n");
        for (WorkspaceItem item : workspaceItems) {
            sb.append("- [")
                    .append(item.kind().name())
                    .append("] ")
                    .append(item.title());
            if (item.summary() != null && !item.summary().isBlank()) {
                sb.append(": ").append(item.summary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    int estimateTokens(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    /**
     * 三因子注入评分 — 融合 relevance（相关度）、importance（重要度）、recency（时近度）。
     *
     * <p>权重从 {@code lifepilot.memory.retrieval.injection-weights} 配置读取，
     * 避免硬编码 0.4 / 0.3 / 0.3。</p>
     *
     * @param entity        目标实体
     * @param relevanceHit  是否命中相关度判定（eval-tag 匹配或关键词匹配）
     * @return 融合评分 [0.0, 1.0]
     */
    private float computeInjectionScore(TemporalEntity entity, boolean relevanceHit) {
        MemoryProperties.InjectionWeights w = memoryProperties != null
                ? memoryProperties.getRetrieval().getInjectionWeights()
                : new MemoryProperties.InjectionWeights();
        float relevance = relevanceHit ? 1.0f : 0.0f;
        float importance = entity.importanceScore();
        // 时近度：30 天内线性衰减到 0
        float recency = 1.0f;
        if (entity.updatedAt() != null) {
            long daysAgo = Duration.between(entity.updatedAt(), Instant.now()).toDays();
            recency = Math.max(0.0f, 1.0f - daysAgo / 30.0f);
        }
        return w.getRelevance() * relevance + w.getImportance() * importance + w.getRecency() * recency;
    }

    /**
     * Skill catalog 一次最多列出多少条 — 超过的依赖 LLM 调 {@code find-skills} 显式发现。
     *
     * <p>排序键：先按 query 关键词命中分降序，再按 priority 升序（HIGH→LOW），最后 name 升序。
     * 命中无差异时 priority 起决定作用；当存在命中分非零的低 priority skill 时，
     * 它会被排序到前面 —— 这是有意行为：query 已经表达明确意图。</p>
     */

    /**
     * 构建 Skill Catalog 段 —— 全量列出所有已启用 skill。
     *
     * <p>过滤链：skills 表（enabled=true） → SkillRegistry 内存定义 →
     * {@link SkillRequirementGate} requires 满足 → 按 query 关键词打分 + priority
     * 排序（命中关键词的排在前面，便于 LLM 优先注意），不做截断。</p>
     *
     * <p>输出渲染进 {@code agent/skill-catalog.st} 模板，
     * XML 标签格式（{@code <skill name="..."><description>...</description></skill>}）；
     * 实测此格式比 markdown list 选取率显著更高，因为 LLM 训练里见过大量类似的
     * tool/function schema 定义，识别为"必须从中选择"的强信号。</p>
     */
    private String buildSkillCatalog(@Nullable ReactAgentState state) {
        if (skillRegistry == null || skillInstallationRepository == null) {
            return "";
        }

        List<SkillInstallation> enabled;
        try {
            enabled = skillInstallationRepository.findAllByEnabled(true);
        } catch (Exception e) {
            log.warn("加载已启用 skill 失败: error={}", e.getMessage());
            return "";
        }
        if (enabled.isEmpty()) {
            return "";
        }

        // 查表 + 过滤：注册表存在 + requires 满足
        List<SkillCatalogEntry> allEntries = enabled.stream()
                .map(install -> skillRegistry.find(install.name()).orElse(null))
                .filter(Objects::nonNull)
                .filter(def -> skillRequirementGate == null
                        || skillRequirementGate.satisfies(def.zhiweiMeta().requires()))
                .map(ContextAssembler::toCatalogEntry)
                .toList();

        if (allEntries.isEmpty()) {
            return "";
        }

        // 关键词打分排序，全量输出（不截断）—— LLM 选取率优先于上下文节省
        String goal = state == null ? null : state.goal();
        List<String> keywords = extractKeywords(goal);
        List<SkillCatalogEntry> selected = sortAllEntries(allEntries, keywords);

        String skillEntries = renderSkillList(selected);
        try {
            return promptRegistry.render("agent/skill-catalog", Map.of("skillEntries", skillEntries));
        } catch (Exception e) {
            log.warn("渲染技能目录失败: error={}", e.getMessage());
            return "";
        }
    }

    /** Skill catalog 一行映射：name + description + tags + priority。 */
    record SkillCatalogEntry(String name, String description, List<String> tags, SkillPriority priority) {}

    /** 把 {@link SkillDefinition} 投影到 {@link SkillCatalogEntry}；优先 frontmatter name，缺则回退 id。 */
    private static SkillCatalogEntry toCatalogEntry(SkillDefinition def) {
        SkillZhiweiMeta meta = def.zhiweiMeta();
        String displayName = (def.name() != null && !def.name().isBlank()) ? def.name() : def.id();
        return new SkillCatalogEntry(displayName, def.description(), meta.tags(), meta.priority());
    }

    /**
     * 从 query 中提取关键词 — 中文按字切，英文按 word 切，去重 + 长度 ≥ 2 过滤。
     * 用于轻量打分，不做分词器级别的精细处理。
     */
    static List<String> extractKeywords(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 中文按 2-gram 滑窗 + 英文 word 切；去重且长度 ≥ 2
        String trimmed = query.trim();
        var result = new java.util.LinkedHashSet<String>();
        // 英文 / 数字 token
        for (String token : trimmed.split("[\\s\\p{Punct}]+")) {
            if (token.length() >= 2 && token.chars().anyMatch(c -> c < 128)) {
                result.add(token.toLowerCase(Locale.ROOT));
            }
        }
        // 中文 2-gram
        for (int i = 0; i < trimmed.length() - 1; i++) {
            char a = trimmed.charAt(i);
            char b = trimmed.charAt(i + 1);
            if (Character.UnicodeScript.of(a) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(b) == Character.UnicodeScript.HAN) {
                result.add(("" + a + b).toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 给 entry 按关键词命中度打分 — name 命中权重最高，description 次之，tags 最低。
     */
    static int scoreEntry(SkillCatalogEntry e, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        String name = e.name() == null ? "" : e.name().toLowerCase(Locale.ROOT);
        String desc = e.description() == null ? "" : e.description().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String kw : keywords) {
            if (name.contains(kw)) score += 5;
            if (desc.contains(kw)) score += 2;
            for (String tag : e.tags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(kw)) score += 1;
            }
        }
        return score;
    }

    /**
     * 按 (score 降, priority 升, name 升) 排序，全量返回（不截断）。
     * 命中 query 关键词的排在前面，让 LLM 优先注意。
     */
    private static List<SkillCatalogEntry> sortAllEntries(List<SkillCatalogEntry> all,
                                                          List<String> keywords) {
        return all.stream()
                .sorted(Comparator
                        .comparingInt((SkillCatalogEntry e) -> -scoreEntry(e, keywords))
                        .thenComparingInt(e -> priorityOrder(e.priority()))
                        .thenComparing(SkillCatalogEntry::name))
                .toList();
    }

    /**
     * 渲染 XML 标签格式 — 每个 skill 一段 {@code <skill name="..."><description>...</description></skill>}；
     * 此格式比 markdown list 选取率显著更高（实测）：LLM 训练里见过大量 tool/function schema 定义，
     * 识别为"必须从中选择"的强信号。
     *
     * <p>description 渲染时剥离"关键词：xxx。"段——关键词列表是给 trigram 检索索引看的（提高
     * 召回率），LLM 看到长串关键词只会浪费上下文，主句 + 反引导（"X 用 Y 不用 Z"）已足够选择。</p>
     */
    private static String renderSkillList(List<SkillCatalogEntry> entries) {
        var sb = new StringBuilder();
        for (SkillCatalogEntry e : entries) {
            sb.append("<skill name=\"").append(e.name()).append("\">")
                    .append("<description>")
                    .append(stripKeywordList(e.description()))
                    .append("</description>")
                    .append("</skill>\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 剥离 description 中的"关键词：A、B、C、...。"片段。
     * 兼容关键词段缺失或位置不同的场景：未命中时返回原文。
     */
    private static final java.util.regex.Pattern KEYWORD_LIST_PATTERN =
            java.util.regex.Pattern.compile("关键词[:：][^。]*。\\s*");

    static String stripKeywordList(@Nullable String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        return KEYWORD_LIST_PATTERN.matcher(description).replaceAll("").trim();
    }

    /** HIGH=0, NORMAL=1, LOW=2 — 排序权重。 */
    private static int priorityOrder(SkillPriority p) {
        if (p == null) {
            return 1;
        }
        return switch (p) {
            case HIGH -> 0;
            case NORMAL -> 1;
            case LOW -> 2;
        };
    }

    /**
     * 已加载 Skill 段 token 上限 —— 超出时按行边界截断；防止用户连续 load 多个长 skill 撑爆 user prompt。
     */
    private static final int LOADED_SKILLS_MAX_TOKENS = 4000;

    /**
     * 构建已加载 Skill 指南段 — 注入到 userPrompt 头部（runtime_context 上方），
     * 利用近因效应确保 LLM 优先注意到 Skill 指南中的约束。
     *
     * <p>{@code state.loadedSkillContent()} 由 {@link com.lifepilot.agent.execution.ToolExecutionCoordinator}
     * 从 {@code skill.load} 工具输出中提取，形如 {@code <skill name="X">body</skill>}，
     * 已经是 {@link com.lifepilot.skill.MarkdownSkillParser} 解析后的 body 部分，
     * 不再含 YAML frontmatter，直接拼接即可。</p>
     *
     * <p>超过 {@value #LOADED_SKILLS_MAX_TOKENS} tokens 时按行边界截断并附提示，
     * 引导 LLM 按需读取 {@code references/} 详细文档。</p>
     */
    private String buildLoadedSkillsSection(@Nullable ReactAgentState state) {
        if (state == null || state.loadedSkillContent() == null || state.loadedSkillContent().isBlank()) {
            return "";
        }
        String content = state.loadedSkillContent().strip();
        int tokens = estimateTokens(content);
        if (tokens > LOADED_SKILLS_MAX_TOKENS) {
            log.warn("loaded_skills 内容超出预算被截断: originalTokens={}, budget={}",
                    tokens, LOADED_SKILLS_MAX_TOKENS);
            content = truncateByTokenBudget(content, LOADED_SKILLS_MAX_TOKENS)
                    + "\n... (已截断，详细内容请按需调用 file.read 加载 {skill_dir}/references/ 下的文档)";
        }
        return "<loaded_skills>\n" + content + "\n</loaded_skills>";
    }

    /** 按 token 预算截断字符串，按行边界切（避免破坏 markdown 结构）。 */
    private String truncateByTokenBudget(String text, int tokenBudget) {
        if (estimateTokens(text) <= tokenBudget) {
            return text;
        }
        var sb = new StringBuilder();
        int used = 0;
        for (String line : text.split("\n", -1)) {
            int lineTokens = estimateTokens(line) + 1; // 算上换行
            if (used + lineTokens > tokenBudget) break;
            sb.append(line).append('\n');
            used += lineTokens;
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 构建 MCP Server 目录 — 列出所有已连接的 MCP Server 供 LLM 按需加载。
     */
    private String buildMcpServerCatalog() {
        if (toolRegistry == null) return "";
        var serverNames = toolRegistry.getRegisteredServerNames();
        if (serverNames.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n<available_mcp_servers>\n");
        for (String serverName : serverNames) {
            var tools = toolRegistry.getToolsByServer(serverName);
            if (tools.isEmpty()) continue;
            String description = getMcpServerDescription(serverName, tools);
            sb.append("<server id=\"").append(serverName)
              .append("\" tools=\"").append(tools.size()).append("\">\n");
            sb.append("  <description>").append(description).append("</description>\n");
            sb.append("</server>\n");
        }
        sb.append("</available_mcp_servers>\n");
        return sb.toString();
    }

    /**
     * 获取 MCP Server 描述 — 优先使用用户配置，否则从工具描述聚合。
     */
    private String getMcpServerDescription(String serverName, List<ToolContract> tools) {
        if (mcpConfig != null) {
            for (var entry : mcpConfig.getServers()) {
                if (serverName.equals(entry.getName())
                        && entry.getDescription() != null
                        && !entry.getDescription().isBlank()) {
                    return entry.getDescription();
                }
            }
        }
        // 没有自定义描述，从工具描述聚合
        return tools.stream()
                .map(ToolContract::description)
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.joining("；"));
    }

    private String safeReactSystemPrompt() {
        return safeReactSystemPrompt(null);
    }

    private String safeReactSystemPrompt(@Nullable ReactAgentState state) {
        try {
            return buildReactSystemPrompt(state);
        } catch (Exception e) {
            log.error("渲染系统提示词失败，使用降级提示词: error={}", e.getMessage());
            ZonedDateTime now = ZonedDateTime.now();
            String timeContext = "当前时间: " + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + ", 时区: " + ZoneId.systemDefault().getId()
                    + ", 区域: " + Locale.getDefault().toLanguageTag();
            return """
                    你是知微，一个可靠、谨慎的 AI 助手。
                    %s
                    请基于当前请求帮助用户。
                    """.formatted(timeContext);
        }
    }

    private void logAssemblyMetrics(ReactAgentState state,
                                    AssembledContext context,
                                    Instant startTime) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
        log.info("上下文组装完成: sessionId={}, totalTokensConsumed={}, assemblyDurationMs={}",
                state.sessionId(), context.totalTokens(), durationMs);
        if (context.degraded()) {
            log.warn("上下文组装已降级: sessionId={}", state.sessionId());
        }
    }

    private String safeRedact(String text) {
        if (dataRedactor == null || text == null || text.isEmpty()) {
            return text;
        }
        try {
            return dataRedactor.redact(text);
        } catch (Exception e) {
            log.warn("数据脱敏失败，改为使用原文: error={}", e.getMessage());
            return text;
        }
    }
}
