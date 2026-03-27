package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.generation.router.GenerationRouter;
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
import lombok.Setter;
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
import java.util.stream.Collectors;

/**
 * 从 transcript、工作区与长期记忆中组装提示词上下文。
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

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
                proceduralMemory, effectivenessTracker, skillRegistry, null, null);
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
                proceduralMemory, effectivenessTracker, skillRegistry, generationRouter, null);
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
    }
    /**
     * 运行时传入的模型上下文窗口，0 表示使用配置值和 Provider 默认值。
     * -- SETTER --
     *  设置运行时传入的模型上下文窗口。
     *
     */
    @Setter
    private volatile int modelContextWindow = 0;

    public AssembledContext assemble(ReactAgentState state) {
        Instant startTime = Instant.now();
        try {
            boolean mediaPlaceholder = isMediaPlaceholderQuery(state.goal());
            int contextWindow = resolveContextWindow(state);
            int totalContextTokens = Math.max(
                    1024,
                    contextWindow - Math.max(0, config.getContext().getOutputReservedTokens()));
            ContextEngine.ContextSnapshot contextSnapshot = safeLoadContextSnapshot(state, totalContextTokens);
            List<WorkspaceItem> workspaceItems = contextSnapshot.workspaceItems();
            String userProfile = mediaPlaceholder ? "" : safeGetUserProfile(state.goal());
            List<TemporalEntity> experiences = mediaPlaceholder ? List.of() : safeRetrieveExperiences(state.goal());
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
        if (state == null || state.channel() == null || state.channel().isBlank()) {
            return "interactive";
        }
        String channel = state.channel().toLowerCase(Locale.ROOT);
        if (channel.startsWith("cron")) {
            return "cron";
        }
        if (channel.startsWith("heartbeat")) {
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
                    - 这是 heartbeat 巡检任务，优先按 checklist 检查状态和新增异常
                    - 不要擅自把巡检任务改成新的 cron 调度
                    - 一切正常时返回 HEARTBEAT_OK
                    """.trim();
            default -> """
                    - 明确时间点或周期任务时，使用 cron
                    - 模糊持续关注类请求时，优先使用 heartbeat/checklist
                    - 一次性分析或执行任务时，直接执行，不创建长期任务
                    """.trim();
        };
    }

    private String buildExecutionGuardPrompt(ReactAgentState state) {
        if (isTaskMode(state)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                <execution_completion_contract>
                - 你需要根据当前请求自行判断这是普通问答，还是需要持续执行的多步任务
                - 普通问答、解释、分析、总结类请求：可以直接正常回答并结束，不需要任何特殊前缀
                - 多步执行类请求：如果还有必要步骤未完成，不要用阶段性总结、计划说明或“接下来继续执行”之类的话结束本轮，应该继续调用工具
                - 如果只是缺少用户补充的信息、确认结果或外部回传结果，不要把这种可恢复阻塞包装成已经失败或已经完成
                - 在 Web 对话中，需要用户补充信息时，直接把整段面向用户的追问包在 <await_user_input>...</await_user_input> 中
                - <await_user_input> 标签内的文本会直接显示给用户，所以要明确说明缺什么、为什么缺，以及补充后会继续做什么
                - 如果这轮没有调用工具、但你已经能够给出终态文本，请在自然语言正文后追加一个隐藏控制标签：
                  1. `<completion_control>done</completion_control>` 表示任务已完成
                  2. `<completion_control>blocked</completion_control>` 表示任务明确阻塞
                  3. `<completion_control>continue</completion_control>` 表示这段文字只是阶段说明，不是终态
                - `completion_control` 只给系统判断使用，不会展示给用户；用户看到的仍应是自然语言正文
                - 如果用户明确要求“就到这里”“输出一个 OK 即可”“这样结束吧”，且当前目标已经满足，可以直接自然收尾，不必为了格式再解释系统状态
                - 不要再额外输出“我先挂起”“等待恢复”之类的系统说明，直接向用户追问即可
                - “创建了目录”“了解了流程”“下一步将继续执行”“现在让我继续处理”都不算完成
                """);
        if (state.earlyStopRejectCount() > 0) {
            sb.append("- 系统已经拒绝过你的一次疑似提前结束；如果这轮要结束，请补上正确的 `<completion_control>` 标签；如果任务还没做完，就继续调用工具\n");
        }
        sb.append("</execution_completion_contract>");
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
        if (modelContextWindow > 0) {
            resolvedWindow = Math.min(resolvedWindow, modelContextWindow);
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

    private String buildSkillCatalog() {
        if (skillRegistry == null) {
            return "";
        }
        var skills = skillRegistry.listAll();
        if (skills.isEmpty()) {
            return "";
        }

        String skillEntries = skillRegistry.listAll().stream()
                .map(skill -> skill.toDiscoverySummary())
                .collect(Collectors.joining("\n"));

        try {
            return promptRegistry.render("agent/skill-catalog", Map.of("skillEntries", skillEntries));
        } catch (Exception e) {
            log.warn("渲染技能目录失败: error={}", e.getMessage());
            return "";
        }
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
