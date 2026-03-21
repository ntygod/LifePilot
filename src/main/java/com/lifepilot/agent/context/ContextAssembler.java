package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceProperties;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从会话历史、临时工作区和长期记忆中组装提示词上下文。
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private static final int DEFAULT_RECENT_TURN_LIMIT = 6;
    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;
    private static final Pattern TIME_HINT_PATTERN = Pattern.compile(
            "\u4eca\u5929|\u6628\u65e5|\u6628\u5929|\u6700\u8fd1\\s*\\d+|\u8fc7\u53bb\\s*\\d+|\u8fd1\\s*\\d+|\u5f53\u5929|\u672c\u5468|\u4e0a\u5468|\u672c\u6708|\u4e0a\u6708|\u5c0f\u65f6|\u5206\u949f|\u5929|\u5468|\u6708|\u5e74|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}:\\d{2}");
    private static final Pattern RECENT_DURATION_PATTERN = Pattern.compile("(?:\u6700\u8fd1|\u8fc7\u53bb|\u8fd1)\\s*(\\d+)\\s*(\u5c0f\u65f6|\u5929|\u5468)");
    private static final Pattern EXPLICIT_RANGE_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?)\\s*(?:\u5230|\u81f3|~|-)\\s*(\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?)");
    private static final Pattern LOWER_BOUND_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?)\\s*(?:\u4e4b\u540e|\u4ee5\u540e)");
    private final AgentConfigProperties config;
    private final PromptRegistry promptRegistry;
    @Nullable private final ConversationViewService conversationViewService;
    @Nullable private final SessionWorkspaceService workspaceService;
    @Nullable private final WorkspaceProperties workspaceProperties;
    @Nullable private final DataRedactor dataRedactor;
    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final PassiveNotificationQueue passiveNotificationQueue;
    @Nullable private final MemoryProperties memoryProperties;
    @Nullable private final ProceduralMemory proceduralMemory;
    @Nullable private final EffectivenessTracker effectivenessTracker;
    @Nullable private final SkillRegistry skillRegistry;
    @Nullable private final LlmRouter llmRouter;

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable ConversationViewService conversationViewService,
                            @Nullable SessionWorkspaceService workspaceService,
                            @Nullable WorkspaceProperties workspaceProperties,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable PassiveNotificationQueue passiveNotificationQueue,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry) {
        this(config, promptRegistry, conversationViewService, workspaceService, workspaceProperties,
                dataRedactor, semanticMemory, passiveNotificationQueue, memoryProperties,
                proceduralMemory, effectivenessTracker, skillRegistry, null);
    }

    public ContextAssembler(AgentConfigProperties config,
                            PromptRegistry promptRegistry,
                            @Nullable ConversationViewService conversationViewService,
                            @Nullable SessionWorkspaceService workspaceService,
                            @Nullable WorkspaceProperties workspaceProperties,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable PassiveNotificationQueue passiveNotificationQueue,
                            @Nullable MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            @Nullable EffectivenessTracker effectivenessTracker,
                            @Nullable SkillRegistry skillRegistry,
                            @Nullable LlmRouter llmRouter) {
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.conversationViewService = conversationViewService;
        this.workspaceService = workspaceService;
        this.workspaceProperties = workspaceProperties;
        this.dataRedactor = dataRedactor;
        this.semanticMemory = semanticMemory;
        this.passiveNotificationQueue = passiveNotificationQueue;
        this.memoryProperties = memoryProperties;
        this.proceduralMemory = proceduralMemory;
        this.effectivenessTracker = effectivenessTracker;
        this.skillRegistry = skillRegistry;
        this.llmRouter = llmRouter;
    }

    public AssembledContext assemble(ReactAgentState state) {
        Instant startTime = Instant.now();
        try {
            boolean mediaPlaceholder = isMediaPlaceholderQuery(state.goal());
            List<ConversationTurnView> recentTurns = safeGetRecentTurns(state.sessionId());
            List<WorkspaceItem> workspaceItems = safeGetWorkspaceItems(state.sessionId());
            String userProfile = mediaPlaceholder ? "" : safeGetUserProfile(state.goal());
            List<TemporalEntity> experiences = mediaPlaceholder ? List.of() : safeRetrieveExperiences(state.goal());
            List<String> injectedIds = recordExperienceInjection(state, experiences);
            String experienceSection = formatExperienceSection(experiences);

            String systemPrompt = safeReactSystemPrompt(state);
            String toolGuide = safeRenderToolGuide();
            if (!toolGuide.isBlank()) {
                systemPrompt = systemPrompt + "\n" + toolGuide;
            }

            String userPrompt = buildEnhancedUserPrompt(
                    state,
                    recentTurns,
                    workspaceItems,
                    userProfile,
                    experienceSection
            );

            TokenBudget tokenBudget = buildTokenBudget(
                    state,
                    systemPrompt,
                    recentTurns,
                    workspaceItems,
                    userProfile,
                    experienceSection
            );

            int workspaceTokens = estimateTokens(formatWorkspaceSection(workspaceItems));
            AssembledContext context = new AssembledContext(
                    systemPrompt,
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

            logAssemblyMetrics(state, context, startTime);
            return context;
        } catch (Exception e) {
            log.warn("上下文组装失败，回退到最小提示词: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return buildFallbackContext(state);
        }
    }

    private AssembledContext buildFallbackContext(ReactAgentState state) {
        String systemPrompt = safeReactSystemPrompt();
        String userPrompt = buildUserPrompt(state);
        TokenBudget budget = buildTokenBudget(state, systemPrompt, List.of(), List.of(), "", "");
        return new AssembledContext(
                systemPrompt,
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

            List<TemporalEntity> experiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
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

    String buildUserPrompt(ReactAgentState state) {
        ZonedDateTime now = ZonedDateTime.now();
        return promptRegistry.render("agent/react-user-prompt-basic", Map.of(
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", ZoneId.systemDefault().getId(),
                "locale", Locale.getDefault().toLanguageTag(),
                "userGoal", state.goal() != null ? state.goal() : "",
                "tokensRemaining", String.valueOf(state.budget().tokensRemaining()),
                "stepCount", String.valueOf(state.stepCount()),
                "timeConstraintSection", buildTimeConstraintSection(state.goal(), now)
        ));
    }

    String buildEnhancedUserPrompt(ReactAgentState state,
                                   List<ConversationTurnView> recentTurns,
                                   List<WorkspaceItem> workspaceItems,
                                   @Nullable String userProfile,
                                   @Nullable String experienceSection) {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        vars.put("timezone", ZoneId.systemDefault().getId());
        vars.put("locale", Locale.getDefault().toLanguageTag());
        vars.put("userGoal", state.goal() != null ? state.goal() : "");
        vars.put("tokensRemaining", String.valueOf(state.budget().tokensRemaining()));
        vars.put("stepCount", String.valueOf(state.stepCount()));

        vars.put("userProfileSection", safeRedact(formatUserProfileSection(userProfile)));
        vars.put("passiveNotificationsSection", formatPassiveNotificationsSection());
        vars.put("conversationHistorySection", safeRedact(formatConversationHistorySection(recentTurns)));
        vars.put("workspaceSection", safeRedact(formatWorkspaceSection(workspaceItems)));
        vars.put("memoriesSection", "");
        vars.put("knowledgeBaseSection", "");
        vars.put("crossSessionSection", "");
        vars.put("toolResultsSection", "");
        vars.put("reasoningContextSection", "");
        vars.put("experienceSection", experienceSection != null ? experienceSection : "");
        vars.put("timeConstraintSection", buildTimeConstraintSection(state.goal(), now));

        return promptRegistry.render("agent/react-user-prompt", vars);
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
                    - 除非缺少执行前提，否则不要反问、不要重复任务描述
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
    private List<ConversationTurnView> safeGetRecentTurns(String sessionId) {
        if (conversationViewService == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            int turnLimit = config.getSession().getMaxRecentTurns() > 0
                    ? config.getSession().getMaxRecentTurns()
                    : DEFAULT_RECENT_TURN_LIMIT;
            return conversationViewService.getRecentTurns(sessionId, turnLimit).stream()
                    .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                    .toList();
        } catch (Exception e) {
            log.warn("加载最近完整轮次失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private List<WorkspaceItem> safeGetWorkspaceItems(String sessionId) {
        if (workspaceService == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        if (workspaceProperties != null && !workspaceProperties.isEnabled()) {
            return List.of();
        }
        try {
            int maxItems = workspaceProperties != null
                    ? workspaceProperties.getPromptMaxItems()
                    : DEFAULT_WORKSPACE_PROMPT_LIMIT;
            return workspaceService.listActive(sessionId).stream()
                    .limit(Math.max(0, maxItems))
                    .toList();
        } catch (Exception e) {
            log.warn("加载工作区条目失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private String safeGetUserProfile(@Nullable String refinedQuery) {
        if (semanticMemory == null) {
            return "";
        }
        try {
            List<TemporalEntity> candidates = new ArrayList<>();
            for (EntityType type : List.of(EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL)) {
                candidates.addAll(semanticMemory.findCurrentByType(type));
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

    private TokenBudget buildTokenBudget(ReactAgentState state,
                                         String systemPrompt,
                                         List<ConversationTurnView> recentTurns,
                                         List<WorkspaceItem> workspaceItems,
                                         @Nullable String userProfile,
                                         @Nullable String experienceSection) {
        int totalTokens = resolveContextBudgetTokens(state);
        TokenBudget base = TokenBudget.allocateDefault(totalTokens, config.getContext().getTokenAllocation());
        String conversationSection = formatConversationHistorySection(recentTurns);
        String workspaceSection = formatWorkspaceSection(workspaceItems);
        String profileSection = formatUserProfileSection(userProfile);
        String notificationSection = formatPassiveNotificationsSection();
        int systemPromptUsed = estimateTokens(systemPrompt);
        int historyUsed = estimateTokens(conversationSection);
        int memoryUsed = estimateTokens(workspaceSection)
                + estimateTokens(profileSection)
                + estimateTokens(notificationSection)
                + estimateTokens(experienceSection);

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
                0
        );
    }

    private int resolveContextBudgetTokens(@Nullable ReactAgentState state) {
        int contextWindow = resolveContextWindow(state);
        int reserved = Math.max(0, config.getContext().getOutputReservedTokens());
        return Math.max(1024, contextWindow - reserved);
    }

    private int resolveContextWindow(@Nullable ReactAgentState state) {
        int fallback = Math.max(1024, config.getContext().getMaxContextTokens());
        if (llmRouter == null) {
            return fallback;
        }
        try {
            var candidates = llmRouter.getAvailableCandidates(
                    config.getLoop().getLlmScene(),
                    ProviderCapability.CHAT,
                    state != null ? state.preferredProvider() : null);
            if (!candidates.isEmpty() && candidates.getFirst().maxContextWindow() > 0) {
                return candidates.getFirst().maxContextWindow();
            }
        } catch (Exception e) {
            log.debug("读取 Provider 上下文窗口失败，回退默认配置: error={}", e.getMessage());
        }
        return fallback;
    }

    private String formatUserProfileSection(@Nullable String userProfile) {
        if (userProfile == null || userProfile.isBlank()) {
            return "";
        }
        return "\n用户画像:\n" + userProfile;
    }

    private String formatPassiveNotificationsSection() {
        List<String> notifications = safeDrainPassiveNotifications();
        if (notifications.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n待处理通知:\n");
        for (String line : notifications) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    private String formatConversationHistorySection(List<ConversationTurnView> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n最近对话:\n");
        for (ConversationTurnView turn : turns.stream()
                .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                .toList()) {
            sb.append("[")
                    .append(turn.role())
                    .append("] ")
                    .append(turn.content())
                    .append('\n');
        }
        return sb.toString();
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

    private String buildTimeConstraintSection(@Nullable String goal, ZonedDateTime now) {
        if (goal == null || goal.isBlank() || !TIME_HINT_PATTERN.matcher(goal).find()) {
            return "";
        }
        List<String> hints = new ArrayList<>();
        ZoneId zoneId = now.getZone();
        if (goal.contains("今天") || goal.contains("当天")) {
            hints.add("- 今天/当天范围: "
                    + now.toLocalDate().atStartOfDay(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + " ~ " + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if (goal.contains("昨天") || goal.contains("昨日")) {
            ZonedDateTime start = now.minusDays(1).toLocalDate().atStartOfDay(zoneId);
            ZonedDateTime end = now.toLocalDate().atStartOfDay(zoneId);
            hints.add("- 昨天范围: "
                    + start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + " ~ " + end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        var recentMatcher = RECENT_DURATION_PATTERN.matcher(goal);
        if (recentMatcher.find()) {
            int amount = Integer.parseInt(recentMatcher.group(1));
            String unit = recentMatcher.group(2);
            ZonedDateTime start = switch (unit) {
                case "小时" -> now.minusHours(amount);
                case "周" -> now.minusWeeks(amount);
                default -> now.minusDays(amount);
            };
            hints.add("- 近期范围参考: " + start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + " ~ " + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        var rangeMatcher = EXPLICIT_RANGE_PATTERN.matcher(goal);
        if (rangeMatcher.find()) {
            hints.add("- 显式时间范围: " + rangeMatcher.group(1) + " ~ " + rangeMatcher.group(2));
        }
        var lowerBoundMatcher = LOWER_BOUND_PATTERN.matcher(goal);
        if (lowerBoundMatcher.find()) {
            hints.add("- 时间下界: " + lowerBoundMatcher.group(1));
        }
        StringBuilder sb = new StringBuilder("""
                <time_constraints>
                - 用户给出时间范围时，先换算成绝对时间区间
                - 支持时间过滤的工具必须显式传入 startTime/endTime（即 `startTime` / `endTime`）
                - 工具不支持时间过滤时，先获取结果，再过滤时间范围外的数据
                - 最终回答优先引用绝对时间，避免相对时间歧义
                """);
        if (!hints.isEmpty()) {
            sb.append("""
                    - 识别到的时间线索:
                    """);
            for (String hint : hints) {
                sb.append(hint).append('\n');
            }
        }
        sb.append("</time_constraints>");
        return sb.toString();
    }
    private List<String> safeDrainPassiveNotifications() {
        if (passiveNotificationQueue == null) {
            return List.of();
        }
        try {
            return passiveNotificationQueue.drainAll().stream()
                    .map(entry -> "[%s] %s".formatted(
                            entry.typeId() != null ? entry.typeId() : "notification",
                            entry.contentJson()))
                    .toList();
        } catch (Exception e) {
            log.warn("读取被动通知失败: error={}", e.getMessage());
            return List.of();
        }
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
        List<?> skills = skillRegistry.listAll();
        if (skills.isEmpty()) {
            return "";
        }

        String skillEntries = skillRegistry.listAll().stream()
                .map(skill -> "- " + skill.id() + ": " + skill.name() + " - " + skill.description())
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
