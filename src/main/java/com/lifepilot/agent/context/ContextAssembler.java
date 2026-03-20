package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.ConversationTurnView;
import com.lifepilot.conversation.ConversationViewService;
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
import java.util.stream.Collectors;

/**
 * Assembles prompt context from conversation history, workspace items and long-term memory.
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private static final int DEFAULT_RECENT_TURN_LIMIT = 6;
    private static final int DEFAULT_WORKSPACE_PROMPT_LIMIT = 3;

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

            String systemPrompt = safeReactSystemPrompt();
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
            log.warn("Context assembly failed, falling back to minimal prompt. sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return buildFallbackContext(state);
        }
    }

    private AssembledContext buildFallbackContext(ReactAgentState state) {
        String systemPrompt = safeReactSystemPrompt();
        String userPrompt = buildUserPrompt(state);
        TokenBudget budget = buildTokenBudget(systemPrompt, List.of(), List.of(), "", "");
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
            log.debug("Experience retrieval skipped: error={}", e.getMessage());
            return List.of();
        }
    }

    String formatExperienceSection(List<TemporalEntity> experiences) {
        if (experiences == null || experiences.isEmpty() || memoryProperties == null) {
            return "";
        }

        int tokenBudget = memoryProperties.getExperience().getInjectionTokenBudget();
        StringBuilder sb = new StringBuilder("\nRelevant experience:\n");
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
        String roleDefinition = promptRegistry.render("agent/role-definition");
        String contextGuide = promptRegistry.render("agent/context-guide");
        ZonedDateTime now = ZonedDateTime.now();
        String systemPrompt = promptRegistry.render("agent/react-system", Map.of(
                "roleDefinition", roleDefinition,
                "contextGuide", contextGuide,
                "currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timezone", ZoneId.systemDefault().getId(),
                "locale", Locale.getDefault().toLanguageTag()
        ));

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
                "stepCount", String.valueOf(state.stepCount())
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

        return promptRegistry.render("agent/react-user-prompt", vars);
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
            log.warn("Failed to load recent turns: sessionId={}, error={}", sessionId, e.getMessage());
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
            log.warn("Failed to load workspace items: sessionId={}, error={}", sessionId, e.getMessage());
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
                    log.warn("Failed to load procedural preferences: error={}", e.getMessage());
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
                sb.append("L3 profile:\n");
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
                sb.append("L4 preferences:\n");
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
            log.warn("Failed to load user profile: error={}", e.getMessage());
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
                log.warn("Failed to record injected experiences: error={}", e.getMessage());
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

    private TokenBudget buildTokenBudget(String systemPrompt,
                                         List<ConversationTurnView> recentTurns,
                                         List<WorkspaceItem> workspaceItems,
                                         @Nullable String userProfile,
                                         @Nullable String experienceSection) {
        int totalTokens = config.getContext().getMaxContextTokens();
        TokenBudget base = TokenBudget.allocateDefault(totalTokens);
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

    private String formatUserProfileSection(@Nullable String userProfile) {
        if (userProfile == null || userProfile.isBlank()) {
            return "";
        }
        return "\nUser profile:\n" + userProfile;
    }

    private String formatPassiveNotificationsSection() {
        List<String> notifications = safeDrainPassiveNotifications();
        if (notifications.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nPending notifications:\n");
        for (String line : notifications) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    private String formatConversationHistorySection(List<ConversationTurnView> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nRecent conversation:\n");
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
        StringBuilder sb = new StringBuilder("\nActive workspace:\n");
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
            log.warn("Failed to drain passive notifications: error={}", e.getMessage());
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
            log.warn("Failed to render skill catalog: error={}", e.getMessage());
            return "";
        }
    }

    private String safeRenderToolGuide() {
        try {
            String guide = promptRegistry.render("memory/agentic-tool-guide");
            return guide != null ? guide : "";
        } catch (Exception e) {
            log.warn("Failed to render tool guide: error={}", e.getMessage());
            return "";
        }
    }

    private String safeReactSystemPrompt() {
        try {
            return buildReactSystemPrompt();
        } catch (Exception e) {
            log.error("Failed to render system prompt, using fallback prompt: error={}", e.getMessage());
            ZonedDateTime now = ZonedDateTime.now();
            String timeContext = "Current time: " + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    + ", timezone: " + ZoneId.systemDefault().getId()
                    + ", locale: " + Locale.getDefault().toLanguageTag();
            return """
                    You are ZhiWei, a reliable and careful AI assistant.
                    %s
                    Please help the user based on the current request.
                    """.formatted(timeContext);
        }
    }

    private void logAssemblyMetrics(ReactAgentState state,
                                    AssembledContext context,
                                    Instant startTime) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
        log.info("Context assembled: sessionId={}, totalTokensConsumed={}, assemblyDurationMs={}",
                state.sessionId(), context.totalTokens(), durationMs);
        if (context.degraded()) {
            log.warn("Context assembly degraded: sessionId={}", state.sessionId());
        }
    }

    private String safeRedact(String text) {
        if (dataRedactor == null || text == null || text.isEmpty()) {
            return text;
        }
        try {
            return dataRedactor.redact(text);
        } catch (Exception e) {
            log.warn("Data redaction failed, using raw text: error={}", e.getMessage());
            return text;
        }
    }
}
