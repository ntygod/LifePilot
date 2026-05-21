package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.agent.learning.experience.EffectivenessTracker;
import com.lifepilot.memory.consumption.hot.HotMemoryDigest;
import com.lifepilot.memory.consumption.hot.HotMemoryDigestService;
import com.lifepilot.memory.consumption.hot.HotMemorySectionKind;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.workspace.WorkspaceItem;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.skill.registry.SkillRegistry;
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

    /** 已格式化上下文片段及其实际进入 prompt 的实体 ID。 */
    record InjectedMemorySection(String text, List<String> entityIds) {
        InjectedMemorySection {
            text = text == null ? "" : text;
            entityIds = entityIds != null ? List.copyOf(entityIds) : List.of();
        }

        boolean isBlank() {
            return text.isBlank();
        }

        static InjectedMemorySection empty() {
            return new InjectedMemorySection("", List.of());
        }
    }

    private static final Duration METADATA_CACHE_TTL = Duration.ofMinutes(5);

    private final AgentConfigProperties config;
    private final LocationResolver locationResolver;
    private final PromptRegistry promptRegistry;
    @Nullable private final DataRedactor dataRedactor;
    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final EffectivenessTracker effectivenessTracker;
    @Nullable private final SkillRegistry skillRegistry;
    @Nullable private final GenerationRouter generationRouter;
    @Nullable private final ContextEngine contextEngine;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final DynamicToolRegistry toolRegistry;
    @Nullable private final McpConfigProperties mcpConfig;
    @Nullable private volatile HotMemoryDigestService hotMemoryDigestService;
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
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory,
                effectivenessTracker, skillRegistry,
                null, null, null, null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory,
                effectivenessTracker, skillRegistry,
                generationRouter, null, null, null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory,
                effectivenessTracker, skillRegistry,
                generationRouter, contextEngine, null, null, null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable KnowledgeBaseRepository knowledgeBaseRepository) {
        this(config, promptRegistry,
                dataRedactor, semanticMemory,
                effectivenessTracker, skillRegistry,
                generationRouter, contextEngine,
                sessionKnowledgeBaseRepository, knowledgeBaseRepository,
                null, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable GenerationRouter generationRouter,
                            @Nullable ContextEngine contextEngine,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
                            @Nullable DynamicToolRegistry toolRegistry,
                            @Nullable McpConfigProperties mcpConfig) {
        this.config = config;
        this.locationResolver = new LocationResolver(config);
        this.promptRegistry = promptRegistry;
        this.dataRedactor = dataRedactor;
        this.semanticMemory = semanticMemory;
        this.effectivenessTracker = effectivenessTracker;
        this.skillRegistry = skillRegistry;
        this.generationRouter = generationRouter;
        this.contextEngine = contextEngine;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.toolRegistry = toolRegistry;
        this.mcpConfig = mcpConfig;
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

    /** 注入热记忆摘要服务（可选）。 */
    public void setHotMemoryDigestService(@Nullable HotMemoryDigestService hotMemoryDigestService) {
        this.hotMemoryDigestService = hotMemoryDigestService;
    }

    public AssembledContext assemble(ReactAgentState state) {
        Instant startTime = Instant.now();
        try {
            boolean mediaPlaceholder = isMediaPlaceholderQuery(state.goal());
            int contextWindow = resolveContextWindow(state);
            int totalContextTokens = Math.max(
                    1024,
                    contextWindow - Math.max(0, config.getContext().getOutputReservedTokens()));
            // 入口一次性 resolve ProjectContext，热摘要与统计复用同一读取视图。
            ProjectContext projectContext = resolveProjectContext(state);
            MemoryReadFilter memoryFilter = toProjectFilter(projectContext,
                    Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT, MemoryScope.AGENT_EXPERIENCE));
            // 默认自动记忆注入只消费 HotMemoryDigest；冷召回由 memory.search / memory.recall 显式触发。
            var contextFuture = CompletableFuture.supplyAsync(
                    () -> safeLoadContextSnapshot(state, totalContextTokens), VIRTUAL_EXECUTOR);
            var hotDigestFuture = mediaPlaceholder
                    ? CompletableFuture.completedFuture((HotMemoryDigest) null)
                    : CompletableFuture.supplyAsync(
                        () -> safeBuildHotMemoryDigest(projectContext, memoryFilter), VIRTUAL_EXECUTOR);
            CompletableFuture.allOf(contextFuture, hotDigestFuture).join();

            ContextEngine.ContextSnapshot contextSnapshot = contextFuture.join();
            List<WorkspaceItem> workspaceItems = contextSnapshot.workspaceItems();
            HotMemoryDigest hotDigest = hotDigestFuture.join();
            InjectedMemorySection rawProfileSection = formatHotDigestSection(
                    hotDigest, Set.of(HotMemorySectionKind.USER_PROFILE));
            InjectedMemorySection rawExperienceSection = formatHotDigestSection(
                    hotDigest, Set.of(HotMemorySectionKind.EXPERIENCE));
            InjectedMemorySection rawMemorySection = formatHotDigestSection(
                    hotDigest, Set.of(HotMemorySectionKind.PROJECT_MEMORY, HotMemorySectionKind.FACTS));

            String profileSection = safeRedact(formatUserProfileSection(rawProfileSection.text()));
            String workspaceSection = safeRedact(formatWorkspaceSection(workspaceItems));
            String artifactSection = safeRedact(contextSnapshot.artifactSection());
            String experienceSection = safeRedact(rawExperienceSection.text());
            String memorySection = safeRedact(rawMemorySection.text());
            List<String> profileInjectedIds = profileSection == null || profileSection.isBlank()
                    ? List.of()
                    : rawProfileSection.entityIds();
            List<String> experienceInjectedIds = experienceSection == null || experienceSection.isBlank()
                    ? List.of()
                    : rawExperienceSection.entityIds();
            List<String> memoryInjectedIds = memorySection == null || memorySection.isBlank()
                    ? List.of()
                    : rawMemorySection.entityIds();
            List<String> injectedIds = mergeInjectedEntityIds(
                    profileInjectedIds, experienceInjectedIds, memoryInjectedIds);
            recordExperienceInjection(state, experienceInjectedIds);
            updateInjectedAccessCounts(injectedIds);

            String systemPrompt = buildAugmentedSystemPrompt(state);
            MemoryCounts memoryCounts = buildInjectedMemoryCounts(
                    rawProfileSection, rawExperienceSection, rawMemorySection);
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

    @Nullable
    private HotMemoryDigest safeBuildHotMemoryDigest(@Nullable ProjectContext projectContext,
                                                     MemoryReadFilter filter) {
        if (hotMemoryDigestService == null) {
            return null;
        }
        try {
            return hotMemoryDigestService.build(filter, hotDigestViewKey(projectContext));
        } catch (Exception e) {
            log.warn("热记忆摘要构建失败，本轮跳过默认记忆注入: error={}", e.getMessage());
            return null;
        }
    }

    private String hotDigestViewKey(@Nullable ProjectContext projectContext) {
        if (projectContext == null || projectContext.projectId() == null || projectContext.projectId().isBlank()) {
            return "personal";
        }
        return "project:" + projectContext.projectId()
                + (projectContext.isolated() ? ":isolated" : ":shared");
    }

    private InjectedMemorySection formatHotDigestSection(@Nullable HotMemoryDigest digest,
                                                         Set<HotMemorySectionKind> sectionKinds) {
        if (digest == null || digest.sections().isEmpty() || sectionKinds == null || sectionKinds.isEmpty()) {
            return InjectedMemorySection.empty();
        }
        List<String> parts = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        for (HotMemoryDigest.HotMemorySection section : digest.sections()) {
            if (!sectionKinds.contains(section.kind()) || section.content().isBlank()) {
                continue;
            }
            parts.add(section.content());
            sourceIds.addAll(section.sourceEntityIds());
        }
        return parts.isEmpty()
                ? InjectedMemorySection.empty()
                : new InjectedMemorySection(String.join("\n\n", parts), sourceIds);
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

    boolean isMediaPlaceholderQuery(@Nullable String goal) {
        if (goal == null || goal.isBlank()) {
            return true;
        }
        String trimmed = goal.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() <= 20;
    }

    String buildReactSystemPrompt(@Nullable ReactAgentState state) {
        String roleDefinition = promptRegistry.render("agent/role-definition");
        String contextGuide = promptRegistry.render("agent/context-guide");
        ZonedDateTime now = ZonedDateTime.now();
        String taskMode = resolveTaskMode(state);
        String systemPrompt;
        if (isTaskMode(state)) {
            systemPrompt = promptRegistry.render("agent/react-system-task", Map.of(
                    "roleDefinition", roleDefinition,
                    "contextGuide", contextGuide,
                    "taskMode", taskMode,
                    "modeSpecificRules", buildModeSpecificRules(taskMode),
                    "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timezone", ZoneId.systemDefault().getId(),
                    "locale", Locale.getDefault().toLanguageTag()
            ));
        } else {
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

    private MemoryCounts buildInjectedMemoryCounts(InjectedMemorySection profileSection,
                                                   InjectedMemorySection experienceSection,
                                                   InjectedMemorySection memorySection) {
        int profileCount = countRenderedEntries(profileSection.text());
        int experienceCount = countRenderedEntries(experienceSection.text());
        int memoryCount = countRenderedEntries(memorySection.text());
        return new MemoryCounts(
                profileCount > 0 ? "共 " + profileCount + " 条热记忆" : "",
                experienceCount > 0 ? "共 " + experienceCount + " 条执行经验" : "",
                memoryCount > 0 ? "共 " + memoryCount + " 条常用记忆" : "");
    }

    private int countRenderedEntries(@Nullable String section) {
        if (section == null || section.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : section.split("\\R")) {
            if (line.stripLeading().startsWith("- ")) {
                count++;
            }
        }
        return count;
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
        String knowledgeBindingPrompt = buildKnowledgeBindingPrompt(state.sessionId(), state.overrideKnowledgeBaseIds());
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

    private String buildKnowledgeBindingPrompt(@Nullable String sessionId, @Nullable List<String> overrideKnowledgeBaseIds) {
        List<String> knowledgeBaseIds;
        if (overrideKnowledgeBaseIds != null && !overrideKnowledgeBaseIds.isEmpty()) {
            // 单轮临时覆盖：完全替换会话持久化绑定（覆盖而非追加）
            knowledgeBaseIds = overrideKnowledgeBaseIds;
        } else if (sessionId != null && !sessionId.isBlank() && sessionKnowledgeBaseRepository != null) {
            knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sessionId);
        } else {
            return "";
        }

        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return "";
        }

        List<String> knowledgeBaseLines = new ArrayList<>();
        for (String knowledgeBaseId : knowledgeBaseIds) {
            knowledgeBaseLines.add(formatKnowledgeBaseBinding(knowledgeBaseId));
        }

        if (knowledgeBaseLines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("<active_knowledge_bindings>\n");
        sb.append("- 当前会话已绑定 Knowledge Base：\n");
        knowledgeBaseLines.forEach(line -> sb.append("  · ").append(line).append('\n'));
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

    private void recordExperienceInjection(ReactAgentState state, List<String> experienceIds) {
        if (experienceIds == null || experienceIds.isEmpty()) {
            return;
        }
        if (effectivenessTracker != null) {
            try {
                effectivenessTracker.recordInjection(state.traceId(), experienceIds);
            } catch (Exception e) {
                log.warn("记录经验注入失败: error={}", e.getMessage());
            }
        }
    }

    @SafeVarargs
    private List<String> mergeInjectedEntityIds(List<String>... idGroups) {
        var merged = new LinkedHashSet<String>();
        if (idGroups != null) {
            for (List<String> ids : idGroups) {
                if (ids == null) {
                    continue;
                }
                ids.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .forEach(merged::add);
            }
        }
        return List.copyOf(merged);
    }

    private void updateInjectedAccessCounts(List<String> injectedIds) {
        if (semanticMemory == null || injectedIds == null || injectedIds.isEmpty()) {
            return;
        }
        for (String id : injectedIds) {
            try {
                semanticMemory.incrementAccessCount(id);
            } catch (Exception e) {
                log.debug("更新注入记忆访问计数失败: entityId={}, error={}", id, e.getMessage());
            }
        }
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

    /** Skill catalog 一行映射：name + description + tags。 */
    record SkillCatalogEntry(String name, String description, List<String> tags) {}

    /** 把 {@link SkillDefinition} 投影到 {@link SkillCatalogEntry}；优先 frontmatter name，缺则回退 id。 */
    private static SkillCatalogEntry toCatalogEntry(SkillDefinition def) {
        SkillZhiweiMeta meta = def.zhiweiMeta();
        String displayName = (def.name() != null && !def.name().isBlank()) ? def.name() : def.id();
        return new SkillCatalogEntry(displayName, def.description(), meta.tags());
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
            // name 受 SkillDefinitionValidator 规范约束（仅 a-z0-9.-），无需 escape；
            // description 是自由文本，加防御性 escape 避免误解析为标签或属性。
            sb.append("<skill name=\"").append(e.name()).append("\">")
                    .append("<description>")
                    .append(escapeXml(stripKeywordList(e.description())))
                    .append("</description>")
                    .append("</skill>\n");
        }
        return sb.toString().stripTrailing();
    }

    /** XML 文本节点最小转义：& < > 转义；引号在文本节点中无需转义。 */
    static String escapeXml(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
            log.warn("数据脱敏失败，跳过该上下文片段: error={}", e.getMessage());
            return "";
        }
    }
}
