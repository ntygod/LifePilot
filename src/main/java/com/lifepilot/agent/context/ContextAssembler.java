package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.working.*;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
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
import java.util.stream.Collectors;

/**
 * 上下文组装器 — Agentic 模式，检索由 Tool 接管，仅保留 L1 会话 + 用户画像 + 通知 + 系统提示词。
 *
 * <p>组装流程：
 * <ol>
 *   <li>从 WorkingMemory 获取 L1 会话历史</li>
 *   <li>查询用户画像（PREFERENCE/HABIT/GOAL）</li>
 *   <li>drain 被动通知队列</li>
 *   <li>通过 TokenBudgetAllocator 动态分配预算</li>
 *   <li>构建 System Prompt + Tool 使用指引</li>
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
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final TokenBudgetAllocator tokenBudgetAllocator;
    @Nullable private final DataRedactor dataRedactor;
    private final PromptRegistry promptRegistry;
    // L3 语义记忆：用户画像查询，可选注入
    @Nullable private final SemanticMemory semanticMemory;
    // 被动通知队列：首次对话时 drain 并注入上下文，可选注入
    @Nullable private final PassiveNotificationQueue passiveNotificationQueue;
    // 记忆配置：用户画像查询等参数，可选注入
    @Nullable private final com.lifepilot.memory.config.MemoryProperties memoryProperties;
    // L4 程序记忆：偏好规则查询，可选注入
    @Nullable private final ProceduralMemory proceduralMemory;
    // 效果追踪器：记录经验注入事件，可选注入
    @Nullable private final com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker;

    /** 基础版构造器（记忆字段为 null）。 */
    public ContextAssembler(AgentConfigProperties config, PromptRegistry promptRegistry) {
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.workingMemory = null;
        this.tokenBudgetAllocator = null;
        this.dataRedactor = null;
        this.semanticMemory = null;
        this.passiveNotificationQueue = null;
        this.memoryProperties = null;
        this.proceduralMemory = null;
        this.effectivenessTracker = null;
    }

    /** 完整版构造器（注入记忆系统依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable SemanticMemory semanticMemory,
                            @Nullable PassiveNotificationQueue passiveNotificationQueue,
                            @Nullable com.lifepilot.memory.config.MemoryProperties memoryProperties,
                            @Nullable ProceduralMemory proceduralMemory,
                            PromptRegistry promptRegistry,
                            @Nullable com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker) {
        this.config = config;
        this.workingMemory = workingMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.dataRedactor = dataRedactor;
        this.promptRegistry = promptRegistry;
        this.semanticMemory = semanticMemory;
        this.passiveNotificationQueue = passiveNotificationQueue;
        this.memoryProperties = memoryProperties;
        this.proceduralMemory = proceduralMemory;
        this.effectivenessTracker = effectivenessTracker;
    }

    /** 判断是否为完整版模式。 */
    private boolean isFullMode() {
        return workingMemory != null && tokenBudgetAllocator != null;
    }

    /**
     * 根据当前状态组装上下文 — 主入口。
     *
     * @param state 当前 Agent 状态
     * @return AssembledContext
     */
    public AssembledContext assemble(ReactAgentState state) {
        if (!isFullMode()) {
            return assembleBasic(state);
        }

        var startTime = Instant.now();
        boolean degraded = false;

        try {
            // 媒体占位符检测：音频/视频消息的 goal 是占位字符，跳过无效处理
            if (isMediaPlaceholderQuery(state.goal())) {
                log.debug("检测到媒体占位符查询，跳过向量检索: sessionId={}, goal={}",
                        state.sessionId(), state.goal());
                return assembleForMediaPlaceholder(state);
            }

            // 1. 获取 L1 会话历史
            var slots = safeGetSessionHistory(workingMemory, state.sessionId(), state.goal());

            // 2. 查询用户画像
            String userProfile = safeGetUserProfile(semanticMemory, state.goal());

            // 3. 动态预算分配
            int conversationTurns = countConversationTurns(slots);
            int sessionMaxTokens = state.budget() != null ? state.budget().maxTokens() : 0;
            var budgetAllocation = safeAllocate(tokenBudgetAllocator, conversationTurns, 0.0f, false, sessionMaxTokens);

            // 4. 按预算截断会话历史
            var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.currentSessionBudget());
            int workingMemoryTokens = truncatedSlots.stream()
                    .mapToInt(WorkingMemorySlot::tokenCount).sum();

            // 5. 构建 System Prompt + Tool 使用指引
            String systemPrompt = buildReactSystemPrompt();
            String toolGuide = safeRenderToolGuide();
            if (!toolGuide.isBlank()) {
                systemPrompt = systemPrompt + "\n" + toolGuide;
            }

            // 6. 构建 TokenBudget
            var tokenBudget = buildTokenBudgetDefault(budgetAllocation, truncatedSlots, systemPrompt);

            // 7. 构建 User Prompt（memories/kbSnippets/crossSession 传空列表）
            // 7a. 检索经验
            var experiences = safeRetrieveExperiences(state.goal());
            String experienceSection = formatExperienceSection(experiences);

            // 7b. 记录经验注入（新增）
            if (effectivenessTracker != null && !experiences.isEmpty()) {
                try {
                    var injectedIds = experiences.stream()
                            .map(com.lifepilot.memory.semantic.TemporalEntity::id).toList();
                    // traceId 从 state 获取
                    effectivenessTracker.recordInjection(state.traceId(), injectedIds);
                } catch (Exception e) {
                    log.warn("经验注入追踪失败: error={}", e.getMessage());
                }
            }

            String userPrompt = buildEnhancedUserPrompt(state, List.of(), List.of(),
                    List.of(), truncatedSlots, userProfile, experienceSection);

            var context = new AssembledContext(
                    systemPrompt, userPrompt, List.of(),
                    tokenBudget, 0, 0.0f,
                    workingMemoryTokens, degraded,
                    List.of(), null);

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
     */
    private AssembledContext assembleForMediaPlaceholder(ReactAgentState state) {
        var slots = safeGetSessionHistory(workingMemory, state.sessionId(), state.goal());

        int conversationTurns = countConversationTurns(slots);
        int sessionMaxTokens = state.budget() != null ? state.budget().maxTokens() : 0;
        var budgetAllocation = safeAllocate(tokenBudgetAllocator, conversationTurns,
                0.0f, false, sessionMaxTokens);

        var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.currentSessionBudget());
        int workingMemoryTokens = truncatedSlots.stream()
                .mapToInt(WorkingMemorySlot::tokenCount).sum();

        String systemPrompt = buildReactSystemPrompt();
        String toolGuide = safeRenderToolGuide();
        if (!toolGuide.isBlank()) {
            systemPrompt = systemPrompt + "\n" + toolGuide;
        }
        var tokenBudget = buildTokenBudgetDefault(budgetAllocation, truncatedSlots, systemPrompt);

        String userPrompt = buildEnhancedUserPrompt(state, List.of(), List.of(),
                List.of(), truncatedSlots, null, null);

        return new AssembledContext(
                systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, workingMemoryTokens, false, List.of(), null);
    }

    // --- 降级容错方法 ---

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
        var result = new ArrayList<>(slots);
        for (int i = result.size() - 1; i >= 0; i--) {
            if (result.get(i) instanceof ConversationSlot cs
                    && "user".equalsIgnoreCase(cs.role())
                    && currentGoal.equals(cs.content())) {
                result.remove(i);
                break;
            }
        }
        return List.copyOf(result);
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
     * @param refinedQuery   查询文本，用于关键词匹配
     * @return 格式化的用户画像文本，无数据时返回空字符串
     */
    private String safeGetUserProfile(@Nullable SemanticMemory semanticMemory, String refinedQuery) {
        if (semanticMemory == null) return "";
        try {
            var candidates = new ArrayList<TemporalEntity>();
            for (var type : List.of(EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL)) {
                candidates.addAll(semanticMemory.findCurrentByType(type));
            }
            if (candidates.isEmpty()) return "";

            int maxEntities = memoryProperties != null
                    ? memoryProperties.getRetrieval().getMaxUserProfileEntities() : 10;
            int fallbackCount = memoryProperties != null
                    ? memoryProperties.getRetrieval().getFallbackUserProfileCount() : 3;

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
                selected = matched.stream()
                        .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                        .limit(maxEntities)
                        .toList();
            } else {
                selected = candidates.stream()
                        .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                        .limit(fallbackCount)
                        .toList();
            }

            if (selected.isEmpty()) return "";

            // L4 高置信度偏好规则查询 + 去重
            List<PreferenceRule> l4Rules = List.of();
            if (proceduralMemory != null) {
                try {
                    l4Rules = proceduralMemory.getPreferences("user-preference").stream()
                            .filter(PreferenceRule::isHighConfidence)
                            .toList();
                } catch (Exception ex) {
                    log.warn("L4 偏好规则查询失败，降级仅使用 L3 实体: error={}", ex.getMessage());
                }
            }

            // L4 优先去重 — 移除与 L4 规则同名的 L3 PREFERENCE 实体
            if (!l4Rules.isEmpty()) {
                var l4Keys = l4Rules.stream()
                        .map(PreferenceRule::key)
                        .collect(Collectors.toSet());
                selected = selected.stream()
                        .filter(e -> !(e.type() == EntityType.PREFERENCE && l4Keys.contains(e.name())))
                        .toList();
            }

            // 格式化 L3 实体
            var profileText = formatUserProfile(selected);

            // 追加 L4 偏好规则
            if (!l4Rules.isEmpty()) {
                var sb = new StringBuilder(profileText);
                for (var rule : l4Rules) {
                    sb.append("- [偏好规则] ").append(rule.key()).append(": ").append(rule.value())
                            .append("（置信度: ").append(String.format("%.2f", rule.confidence())).append("）\n");
                }
                return sb.toString();
            }

            return profileText;
        } catch (Exception e) {
            log.warn("用户画像查询失败，降级跳过: error={}", e.getMessage());
            return "";
        }
    }

    /**
     * 格式化用户画像实体为结构化文本。
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

    // --- 截断方法 ---

    /** 按重要度降序截断会话槽位到 Token 预算内。 */
    List<WorkingMemorySlot> truncateSlotsByBudget(List<WorkingMemorySlot> slots, int tokenBudget) {
        if (slots.isEmpty()) return List.of();

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

    /** 构建 TokenBudget（简化版：仅 systemPrompt + currentSession + userProfile + userMessage）。 */
    private TokenBudget buildTokenBudgetDefault(BudgetAllocation allocation,
                                                List<WorkingMemorySlot> slots,
                                                String systemPrompt) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var staticBudget = TokenBudget.allocateDefault(totalTokens);

        int systemPromptUsed = estimateTokens(systemPrompt);
        int historyUsed = slots.stream().mapToInt(WorkingMemorySlot::tokenCount).sum();

        return new TokenBudget(
                allocation.systemPromptBudget(),
                allocation.currentSessionBudget(),
                allocation.knowledgeEntityBudget(),
                staticBudget.toolSchemaBudget(),
                staticBudget.toolResultBudget(),
                staticBudget.reservedBuffer(),
                systemPromptUsed,
                historyUsed,
                0, // memoryUsed — 检索由 Tool 接管，不再预填充
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
     * 安全渲染 memory/agentic-tool-guide 模板，异常时返回空字符串。
     *
     * @return Tool 使用指引文本
     */
    private String safeRenderToolGuide() {
        try {
            String guide = promptRegistry.render("memory/agentic-tool-guide");
            return guide != null ? guide : "";
        } catch (Exception e) {
            log.warn("Tool 使用指引模板渲染失败，降级跳过: error={}", e.getMessage());
            return "";
        }
    }

    /**
     * 增强系统提示词 — 追加流式约束和可选的 A2UI 提示词。
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
     * <p>使用 {@code agent/react-user-prompt} 模板渲染，各区域数据预格式化后作为模板变量传入。</p>
     */
    String buildEnhancedUserPrompt(ReactAgentState state,
                                   List<String> memories,
                                   List<String> knowledgeBaseSnippets,
                                   List<String> crossSessionFragments,
                                   List<WorkingMemorySlot> slots,
                                   @Nullable String userProfile,
                                   @Nullable String experienceSection) {
        var now = ZonedDateTime.now();
        var vars = new java.util.HashMap<String, Object>();
        vars.put("currentDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        vars.put("timezone", ZoneId.systemDefault().getId());
        vars.put("locale", Locale.getDefault().toLanguageTag());
        vars.put("userGoal", state.goal() != null ? state.goal() : "");
        vars.put("tokensRemaining", String.valueOf(state.budget().tokensRemaining()));
        vars.put("stepCount", String.valueOf(state.stepCount()));

        vars.put("userProfileSection", safeRedact(formatUserProfileSection(userProfile)));
        vars.put("passiveNotificationsSection", formatPassiveNotificationsSection());
        vars.put("conversationHistorySection", safeRedact(formatConversationHistorySection(slots)));
        vars.put("memoriesSection", formatListSection("相关记忆", memories));
        vars.put("knowledgeBaseSection", formatListSection("知识库片段", knowledgeBaseSnippets));
        vars.put("crossSessionSection", formatListSection("跨会话参考", crossSessionFragments));
        vars.put("toolResultsSection", formatToolResultsSection(slots));
        vars.put("reasoningContextSection", formatReasoningContextSection(slots));
        vars.put("experienceSection", experienceSection != null ? experienceSection : "");

        return promptRegistry.render("agent/react-user-prompt", vars);
    }

    /**
     * 构建基础版 User Prompt（无记忆检索）。
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

    // --- 经验检索与注入 ---

    /**
     * 安全检索 EXPERIENCE 类型实体，异常时返回空列表。
     *
     * @param query 查询文本（当前未使用，预留语义检索扩展）
     * @return 按 importanceScore 降序排列的经验实体列表
     */
    List<TemporalEntity> safeRetrieveExperiences(@Nullable String query) {
        if (semanticMemory == null || memoryProperties == null) return List.of();
        try {
            var config = memoryProperties.getExperience();
            if (!config.isEnabled()) return List.of();

            var experiences = semanticMemory.findCurrentByType(
                    com.lifepilot.memory.semantic.EntityType.EXPERIENCE);
            if (experiences.isEmpty()) return List.of();

            // executionContext 过滤
            if (memoryProperties != null) {
                var isolationConfig = memoryProperties.getExperience().getIsolation();
                if (!isolationConfig.isCrossContextRetrieval()) {
                    experiences = experiences.stream()
                            .filter(e -> {
                                var ctx = e.properties().get("executionContext");
                                // null 视为 MAIN_AGENT（向后兼容）
                                return ctx == null
                                        || "MAIN_AGENT".equals(ctx.toString());
                            })
                            .toList();
                }
            }

            // 按 importanceScore 降序排序，eval 标签匹配的经验优先
            String evalPrefix = config.getEvalTagPrefix();
            return experiences.stream()
                    .sorted((a, b) -> {
                        // eval 标签匹配度加权
                        int aEvalBoost = hasMatchingEvalTag(a, query, evalPrefix) ? 1 : 0;
                        int bEvalBoost = hasMatchingEvalTag(b, query, evalPrefix) ? 1 : 0;
                        if (aEvalBoost != bEvalBoost) return bEvalBoost - aEvalBoost;
                        return Float.compare(b.importanceScore(), a.importanceScore());
                    })
                    .limit(config.getMaxInjectionCount())
                    .toList();
        } catch (Exception e) {
            log.debug("经验检索失败，跳过注入: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查经验实体的 applicableConditions 中是否有与查询匹配的 eval 标签。
     */
    private boolean hasMatchingEvalTag(com.lifepilot.memory.semantic.TemporalEntity entity,
                                       @Nullable String query, String evalPrefix) {
        if (query == null || query.isBlank()) return false;
        var props = entity.properties();
        if (props == null) return false;
        var conditions = props.get("applicableConditions");
        if (!(conditions instanceof List<?> list)) return false;
        String lowerQuery = query.toLowerCase();
        for (var item : list) {
            if (item instanceof String tag && tag.startsWith(evalPrefix)) {
                String tagValue = tag.substring(evalPrefix.length()).toLowerCase();
                if (lowerQuery.contains(tagValue)) return true;
            }
        }
        return false;
    }

    /**
     * 格式化经验实体为提示词区段。
     *
     * @param experiences 经验实体列表
     * @return 格式化后的经验区段文本，无经验时返回空字符串
     */
    String formatExperienceSection(List<TemporalEntity> experiences) {
        if (experiences == null || experiences.isEmpty()) return "";
        if (memoryProperties == null) return "";

        int tokenBudget = memoryProperties.getExperience().getInjectionTokenBudget();
        var sb = new StringBuilder("\n相关经验:\n");
        int usedTokens = 0;

        for (var exp : experiences) {
            String entry = "- " + exp.name() + ": " + exp.description() + "\n";
            int entryTokens = estimateTokens(entry);
            if (usedTokens + entryTokens > tokenBudget) break;
            sb.append(entry);
            usedTokens += entryTokens;
        }

        return sb.length() > "相关经验:\n".length() + 1 ? sb.toString() : "";
    }

    /** 记录组装指标。 */
    private void logAssemblyMetrics(ReactAgentState state, AssembledContext context, Instant startTime) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();

        log.info("上下文组装完成: sessionId={}, totalTokensConsumed={}, assemblyDurationMs={}",
                state.sessionId(), context.totalTokens(), durationMs);

        if (context.degraded()) {
            log.warn("上下文组装降级: sessionId={}", state.sessionId());
        }
    }

    // --- 工具方法 ---

    /**
     * 安全脱敏 — dataRedactor 为 null 时跳过，异常时降级使用原始文本。
     */
    private String safeRedact(String text) {
        if (dataRedactor == null || text == null || text.isEmpty()) return text;
        try {
            return dataRedactor.redact(text);
        } catch (Exception e) {
            log.warn("DataRedactor 脱敏失败，降级使用原始文本: error={}", e.getMessage());
            return text;
        }
    }

    /** 截断文本到指定长度。 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
