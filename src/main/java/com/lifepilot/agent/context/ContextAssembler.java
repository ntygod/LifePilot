package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
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
import java.util.stream.Collectors;

/**
 * 从 transcript、工作区与长期记忆中组装提示词上下文。
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper SHARED_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;
    private final AgentConfigProperties config;
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
                null, null, null, null, null, null);
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
                generationRouter, null, null, null, null, null);
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
                generationRouter, contextEngine, null, null, null, null);
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
        this.config = config;
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
    }
    public AssembledContext assemble(ReactAgentState state) {
        Instant startTime = Instant.now();
        try {
            boolean mediaPlaceholder = isMediaPlaceholderQuery(state.goal());
            int contextWindow = resolveContextWindow(state);
            int totalContextTokens = Math.max(
                    1024,
                    contextWindow - Math.max(0, config.getContext().getOutputReservedTokens()));
            // 三路独立检索并行化：contextSnapshot、userProfile、experiences 互不依赖
            var contextFuture = CompletableFuture.supplyAsync(
                    () -> safeLoadContextSnapshot(state, totalContextTokens));
            var profileFuture = mediaPlaceholder
                    ? CompletableFuture.completedFuture("")
                    : CompletableFuture.supplyAsync(() -> safeGetUserProfile(state.goal()));
            var experiencesFuture = mediaPlaceholder
                    ? CompletableFuture.completedFuture(List.<TemporalEntity>of())
                    : CompletableFuture.supplyAsync(() -> safeRetrieveExperiences(state.goal()));
            CompletableFuture.allOf(contextFuture, profileFuture, experiencesFuture).join();

            ContextEngine.ContextSnapshot contextSnapshot = contextFuture.join();
            List<WorkspaceItem> workspaceItems = contextSnapshot.workspaceItems();
            String userProfile = profileFuture.join();
            List<TemporalEntity> experiences = experiencesFuture.join();
            List<String> injectedIds = recordExperienceInjection(state, experiences);
            String profileSection = safeRedact(formatUserProfileSection(userProfile));
            String workspaceSection = safeRedact(formatWorkspaceSection(workspaceItems));
            String artifactSection = safeRedact(contextSnapshot.artifactSection());
            String experienceSection = safeRedact(formatExperienceSection(experiences));

            String systemPrompt = buildAugmentedSystemPrompt(state);
            List<Message> contextMessages = buildContextMessages(
                    profileSection,
                    workspaceSection,
                    artifactSection,
                    experienceSection
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

    List<TemporalEntity> safeRetrieveExperiences(@Nullable String query) {
        if (semanticMemory == null || memoryProperties == null) {
            return List.of();
        }
        try {
            MemoryProperties.Experience experience = memoryProperties.getExperience();
            if (!experience.isEnabled()) {
                return List.of();
            }

            List<TemporalEntity> experiences = semanticMemory.findCurrentByType(
                    EntityType.EXPERIENCE,
                    MemoryReadFilter.agentExperience());
            if (experiences.isEmpty()) {
                return List.of();
            }

            if (!experience.getIsolation().isCrossContextRetrieval()) {
                experiences = experiences.stream()
                        .filter(entity -> {
                            Object executionContext = entity.properties().get("executionContext");
                            return executionContext == null || "MAIN_AGENT".equals(executionContext.toString());
                        })
                        .toList();
            }

            String evalPrefix = experience.getEvalTagPrefix();
            return experiences.stream()
                    .sorted((left, right) -> {
                        int leftBoost = hasMatchingEvalTag(left, query, evalPrefix) ? 1 : 0;
                        int rightBoost = hasMatchingEvalTag(right, query, evalPrefix) ? 1 : 0;
                        if (leftBoost != rightBoost) {
                            return rightBoost - leftBoost;
                        }
                        return Float.compare(right.importanceScore(), left.importanceScore());
                    })
                    .limit(experience.getMaxInjectionCount())
                    .toList();
        } catch (Exception e) {
            log.debug("经验检索已跳过: error={}", e.getMessage());
            return List.of();
        }
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

        String skillCatalog = buildSkillCatalog();
        if (!skillCatalog.isBlank()) {
            systemPrompt = systemPrompt + "\n" + skillCatalog;
        }
        return systemPrompt;
    }

    public String enhanceSystemPromptForStreaming(String baseSystemPrompt,
                                                  @Nullable String a2uiPrompt) {
        String streamingConstraint = promptRegistry.render("agent/streaming-constraint");
        StringBuilder sb = new StringBuilder(baseSystemPrompt != null ? baseSystemPrompt : "");
        if (streamingConstraint != null && !streamingConstraint.isBlank()) {
            sb.append("\n").append(streamingConstraint);
        }
        if (a2uiPrompt != null && !a2uiPrompt.isBlank()) {
            sb.append("\n").append(a2uiPrompt);
        }
        return sb.toString();
    }

    String buildAugmentedSystemPrompt(ReactAgentState state) {
        String baseSystemPrompt = safeReactSystemPrompt(state);
        String toolGuide = safeRenderToolGuide();
        String executionGuard = buildExecutionGuardPrompt(state);
        return joinNonBlankSections(
                baseSystemPrompt,
                toolGuide,
                executionGuard
        );
    }

    String buildUserPrompt(ReactAgentState state) {
        ZonedDateTime now = ZonedDateTime.now();
        String userPrompt = promptRegistry.render("agent/react-user-prompt", Map.of(
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", now.getZone().getId(),
                "osName", System.getProperty("os.name", "unknown"),
                "osVersion", System.getProperty("os.version", "unknown"),
                "channel", state.channel() != null ? state.channel() : "unknown",
                "userGoal", state.goal() != null ? state.goal() : ""
        ));
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
        sb.append("%s [%s] (%s)".formatted(collection.name(), collection.type().name(), collection.id()));
        if (collection.description() != null && !collection.description().isBlank()) {
            sb.append(" — ").append(collection.description());
        }

        // 解析并追加字段定义，让 Agent 知道可以按哪些字段做结构化查询
        if (collection.propertiesJson() != null && !collection.propertiesJson().isBlank()
                && !"[]".equals(collection.propertiesJson().strip())) {
            try {
                var props = SHARED_MAPPER.readValue(
                        collection.propertiesJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                java.util.List<com.lifepilot.datastore.model.PropertyDefinition>>() {});
                if (!props.isEmpty()) {
                    String fieldList = props.stream()
                            .map(p -> "%s(%s%s)".formatted(
                                    p.name(), p.type().name(),
                                    p.required() ? ",必填" : ""))
                            .collect(java.util.stream.Collectors.joining(", "));
                    sb.append("\n    字段: ").append(fieldList);
                }
            } catch (Exception ignored) {
                // 属性定义解析失败时静默跳过，不影响绑定展示
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
                                       @Nullable String experienceSection) {
        List<Message> messages = new ArrayList<>();
        addTaggedContextMessage(messages, "user_profile_context", profileSection);
        addTaggedContextMessage(messages, "workspace_context", workspaceSection);
        addTaggedContextMessage(messages, "artifact_context", artifactSection);
        addTaggedContextMessage(messages, "experience_context", experienceSection);
        return List.copyOf(messages);
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
                - 多步任务：有必要步骤未完成时继续调用工具，不要用阶段性总结结束本轮
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

    private String safeGetUserProfile(@Nullable String refinedQuery) {
        if (semanticMemory == null) {
            return "";
        }
        try {
            List<TemporalEntity> candidates = new ArrayList<>();
            MemoryReadFilter profileFilter = MemoryReadFilter.userProfile();
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
                            .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                            .limit(maxEntities)
                            .toList()
                    : candidates.stream()
                            .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
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

    private boolean hasMatchingEvalTag(TemporalEntity entity,
                                       @Nullable String query,
                                       String evalPrefix) {
        if (query == null || query.isBlank()) {
            return false;
        }
        Object conditions = entity.properties().get("applicableConditions");
        if (!(conditions instanceof List<?> items)) {
            return false;
        }
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (Object item : items) {
            if (item instanceof String tag && tag.startsWith(evalPrefix)) {
                String tagValue = tag.substring(evalPrefix.length()).toLowerCase(Locale.ROOT);
                if (lowerQuery.contains(tagValue)) {
                    return true;
                }
            }
        }
        return false;
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
        return "\n用户画像:\n" + userProfile;
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
     * 构建技能目录摘要 — 用分类概览替代完整列表，节省 ~1700 token/次。
     *
     * <p>不再将 33 个 skill 的完整 XML 摘要塞入 system prompt，
     * 改为生成一行分类概览。LLM 通过 meta.search_tools 按需发现具体 skill。</p>
     */
    private String buildSkillCatalog() {
        if (skillRegistry == null) {
            return "";
        }
        var skills = skillRegistry.listAll();
        if (skills.isEmpty()) {
            return "";
        }

        // 提取所有 skill 名称，按逗号分隔生成紧凑概览
        String nameList = skills.stream()
                .map(skill -> skill.name())
                .collect(Collectors.joining("、"));

        return """
            <skill_overview>
            你有 %d 个技能可用，覆盖：%s。
            使用 meta.search_tools 搜索相关工具和技能，或调用 load_skill 加载已知技能的完整指南。
            </skill_overview>""".formatted(skills.size(), nameList);
    }

    private String safeRenderToolGuide() {
        try {
            String guide = promptRegistry.render("memory/agentic-tool-guide");
            return guide != null ? guide : "";
        } catch (Exception e) {
            log.warn("渲染记忆工具使用指引失败: error={}", e.getMessage());
            return "";
        }
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
