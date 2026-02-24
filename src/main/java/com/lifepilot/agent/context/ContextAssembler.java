package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.StepRecord;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.working.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /** 基础版构造器（向后兼容，记忆字段为 null）。 */
    public ContextAssembler(AgentConfigProperties config) {
        this.config = config;
        this.hybridRetriever = null;
        this.workingMemory = null;
        this.tokenBudgetAllocator = null;
        this.retrievalStrategy = null;
    }

    /** 完整版构造器（注入记忆系统依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            HybridRetriever hybridRetriever,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            MemoryRetrievalStrategy retrievalStrategy) {
        this.config = config;
        this.hybridRetriever = hybridRetriever;
        this.workingMemory = workingMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.retrievalStrategy = retrievalStrategy;
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
    public AssembledContext assemble(AgentState state) {
        // 基础版走原有逻辑
        if (!isFullMode()) {
            return assembleBasic(state);
        }

        var startTime = Instant.now();
        boolean degraded = false;

        try {
            // 1. 获取检索策略
            var strategyConfig = retrievalStrategy.getStrategy(state.phase());

            if (strategyConfig.skip()) {
                return buildMinimalContext(state);
            }

            // 2. 执行记忆检索（降级容错）
            var retrievalResults = safeRetrieve(state.goal(), strategyConfig);
            int retrievalCount = retrievalResults.size();
            float topScore = retrievalResults.isEmpty() ? 0.0f
                    : retrievalResults.getFirst().fusedScore();
            if (retrievalResults.isEmpty() && state.goal() != null) {
                // 检索返回空可能是降级
                degraded = true;
            }

            // 3. 获取会话槽位（降级容错）
            var slots = safeGetContext(state.sessionId());

            // 4. 动态预算分配（降级容错）
            int conversationTurns = countConversationTurns(slots);
            var budgetAllocation = safeAllocate(conversationTurns, topScore);

            // 5. 按预算截断
            var truncatedMemories = truncateByBudget(retrievalResults, budgetAllocation.retrievalBudget());
            var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.workingMemoryBudget());

            // 6. 格式化检索结果
            var formattedMemories = formatRetrievalResults(truncatedMemories);
            int workingMemoryTokens = truncatedSlots.stream()
                    .mapToInt(WorkingMemorySlot::tokenCount).sum();

            // 7. 构建 TokenBudget
            var tokenBudget = buildTokenBudget(state.phase(), budgetAllocation,
                    formattedMemories, truncatedSlots);

            // 8. 构建 Prompt
            String systemPrompt = buildSystemPrompt(state.phase());
            String userPrompt = buildEnhancedUserPrompt(state, formattedMemories, truncatedSlots);

            var context = new AssembledContext(
                    systemPrompt, userPrompt, formattedMemories,
                    tokenBudget, retrievalCount, topScore,
                    workingMemoryTokens, degraded);

            // 9. 日志
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
    private AssembledContext assembleBasic(AgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocate(state.phase(), totalTokens);
        String systemPrompt = buildSystemPrompt(state.phase());
        String userPrompt = buildUserPrompt(state);
        return new AssembledContext(systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, 0, false);
    }

    /** TERMINATED 阶段返回最小化上下文。 */
    private AssembledContext buildMinimalContext(AgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocate(state.phase(), totalTokens);
        return new AssembledContext("", "", List.of(), tokenBudget,
                0, 0.0f, 0, false);
    }

    /** 异常兜底降级上下文。 */
    private AssembledContext buildFallbackContext(AgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocate(state.phase(), totalTokens);
        String systemPrompt = buildSystemPrompt(state.phase());
        String userPrompt = buildUserPrompt(state);
        return new AssembledContext(systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, 0, true);
    }

    // --- 降级容错方法 ---

    /** 安全执行记忆检索，异常时返回空列表。 */
    private List<RetrievalResult> safeRetrieve(String query, RetrievalStrategyConfig config) {
        try {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            return hybridRetriever.retrieve(query, config.topK(), config.weights());
        } catch (Exception e) {
            log.warn("记忆检索降级: query={}, error={}", truncate(query, 50), e.getMessage());
            return List.of();
        }
    }

    /** 安全获取会话槽位，异常时返回空列表。 */
    private List<WorkingMemorySlot> safeGetContext(String sessionId) {
        try {
            return workingMemory.getContext(sessionId);
        } catch (Exception e) {
            log.warn("工作记忆降级: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 安全执行预算分配，异常时使用静态分配降级。 */
    private BudgetAllocation safeAllocate(int conversationTurns, float topScore) {
        try {
            int windowSize = config.getContext().getMaxContextTokens();
            return tokenBudgetAllocator.allocate(windowSize, conversationTurns, topScore);
        } catch (Exception e) {
            log.warn("预算分配降级: error={}", e.getMessage());
            int total = config.getContext().getMaxContextTokens();
            return new BudgetAllocation(
                    (int) (total * 0.10), (int) (total * 0.50),
                    (int) (total * 0.25), (int) (total * 0.15), total);
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

    /** 格式化单条检索结果。 */
    private String formatSingleResult(RetrievalResult result) {
        var sb = new StringBuilder();
        sb.append("[").append(result.entityType()).append("] ")
                .append(result.name());
        if (result.description() != null && !result.description().isBlank()) {
            sb.append(": ").append(result.description());
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

    /** 估算文本 Token 数（中英文混合约 2 字符/Token）。 */
    int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 2);
    }

    // --- TokenBudget 构建 ---

    /** 构建 TokenBudget，映射 BudgetAllocation 到 TokenBudget 槽位。 */
    private TokenBudget buildTokenBudget(AgentPhase phase, BudgetAllocation allocation,
                                         List<String> formattedMemories,
                                         List<WorkingMemorySlot> slots) {
        // 静态分配获取 toolSchema/toolResult/reserved 的比例
        int totalTokens = config.getContext().getMaxContextTokens();
        var staticBudget = TokenBudget.allocate(phase, totalTokens);

        // 计算实际消耗
        int systemPromptUsed = estimateTokens(buildSystemPrompt(phase));
        int historyUsed = slots.stream().mapToInt(WorkingMemorySlot::tokenCount).sum();
        int memoryUsed = formattedMemories.stream().mapToInt(this::estimateTokens).sum();

        return new TokenBudget(
                allocation.systemPromptBudget(),
                allocation.workingMemoryBudget(),
                allocation.retrievalBudget(),
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
     * 构建阶段专用 System Prompt。
     *
     * @param phase 当前阶段
     * @return System Prompt 文本
     */
    String buildSystemPrompt(AgentPhase phase) {
        String roleDefinition = "你是 LifePilot，一个智能个人助手。";
        String phaseInstruction = switch (phase) {
            case UNDERSTANDING -> "当前阶段：意图理解。分析用户输入，提取关键实体和意图，评估任务复杂度。"
                    + "如果需要澄清，设置 needsClarification=true 并提供澄清问题。"
                    + "如果可以继续，设置 canProceed=true。";
            case PLANNING -> "当前阶段：任务规划。根据理解的意图制定执行计划，"
                    + "列出需要调用的工具及其参数，评估所需 Token 预算。";
            case EXECUTING -> "当前阶段：工具执行。按计划执行工具调用，"
                    + "记录每步结果，判断是否还有后续步骤。";
            case REFLECTING -> "当前阶段：反思评估。评估执行结果是否满足用户意图，"
                    + "决定是否需要重新规划或直接生成响应。";
            case RESPONDING -> "当前阶段：生成响应。基于执行结果生成最终回复，"
                    + "提供清晰、有用的信息。";
            case TERMINATED -> "";
        };
        String constraint = "请严格按照 JSON 格式输出结构化结果。";

        return roleDefinition + "\n" + phaseInstruction + "\n" + constraint;
    }

    /**
     * 构建增强版 User Prompt（结构化内容区域）。
     * 顺序：用户请求 → 相关记忆 → 对话历史 → 工具结果 → 推理上下文 → 已执行步骤 → 预算剩余
     */
    String buildEnhancedUserPrompt(AgentState state,
                                   List<String> memories,
                                   List<WorkingMemorySlot> slots) {
        var sb = new StringBuilder();

        // 1. 用户请求（最重要，放在开头）
        sb.append("用户请求: ").append(state.goal()).append("\n");

        // 2. 相关记忆（条件性区域）
        if (!memories.isEmpty()) {
            sb.append("\n相关记忆:\n");
            for (var memory : memories) {
                sb.append("  - ").append(memory).append("\n");
            }
        }

        // 3. 对话历史（条件性区域，按 createdAt 时间顺序）
        var conversationSlots = slots.stream()
                .filter(s -> s instanceof ConversationSlot)
                .map(s -> (ConversationSlot) s)
                .sorted(Comparator.comparing(ConversationSlot::createdAt))
                .toList();
        if (!conversationSlots.isEmpty()) {
            sb.append("\n对话历史:\n");
            for (var cs : conversationSlots) {
                sb.append("  [").append(cs.role()).append("] ").append(cs.content()).append("\n");
            }
        }

        // 4. 工具结果（条件性区域）
        var toolSlots = slots.stream()
                .filter(s -> s instanceof ToolResultSlot)
                .map(s -> (ToolResultSlot) s)
                .toList();
        if (!toolSlots.isEmpty()) {
            sb.append("\n工具结果:\n");
            for (var ts : toolSlots) {
                sb.append("  ").append(ts.toolId()).append(".").append(ts.toolAction())
                        .append(" → ").append(truncate(ts.result(), 200)).append("\n");
            }
        }

        // 5. 推理上下文（条件性区域）
        var reasoningSlots = slots.stream()
                .filter(s -> s instanceof ReasoningSlot)
                .map(s -> (ReasoningSlot) s)
                .toList();
        if (!reasoningSlots.isEmpty()) {
            sb.append("\n推理上下文:\n");
            for (var rs : reasoningSlots) {
                sb.append("  [").append(rs.source()).append("] ").append(rs.thought()).append("\n");
            }
        }

        // 6. 已执行步骤
        if (!state.steps().isEmpty()) {
            sb.append("\n已执行步骤:\n");
            for (int i = 0; i < state.steps().size(); i++) {
                StepRecord step = state.steps().get(i);
                sb.append("  ").append(i + 1).append(". ")
                        .append(step.toolId() != null ? step.toolId() : "系统")
                        .append(" - ").append(step.success() ? "成功" : "失败")
                        .append(": ").append(truncate(step.output(), 200))
                        .append("\n");
            }
        }

        // 7. 预算剩余（放在末尾）
        sb.append("\n预算剩余: Token=").append(state.budget().tokensRemaining())
                .append(", 已用步骤=").append(state.stepCount()).append("\n");

        return sb.toString();
    }

    /**
     * 构建基础版 User Prompt（无记忆检索）。
     *
     * @param state 当前 Agent 状态
     * @return User Prompt 文本
     */
    String buildUserPrompt(AgentState state) {
        var sb = new StringBuilder();
        sb.append("用户请求: ").append(state.goal()).append("\n");
        sb.append("预算剩余: Token=").append(state.budget().tokensRemaining())
                .append(", 已用步骤=").append(state.stepCount()).append("\n");

        if (!state.steps().isEmpty()) {
            sb.append("已执行步骤:\n");
            for (int i = 0; i < state.steps().size(); i++) {
                StepRecord step = state.steps().get(i);
                sb.append("  ").append(i + 1).append(". ")
                        .append(step.toolId() != null ? step.toolId() : "系统")
                        .append(" - ").append(step.success() ? "成功" : "失败")
                        .append(": ").append(truncate(step.output(), 200))
                        .append("\n");
            }
        }

        if (state.plan() != null) {
            sb.append("当前计划: ").append(state.plan().rationale()).append("\n");
            sb.append("计划进度: ").append(state.planStepIndex())
                    .append("/").append(state.plan().steps().size()).append("\n");
        }

        return sb.toString();
    }

    // --- 可观测性日志 ---

    /** 记录组装指标。 */
    private void logAssemblyMetrics(AgentState state, AssembledContext context, Instant startTime) {
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();

        log.info("上下文组装完成: phase={}, sessionId={}, totalTokensConsumed={}, assemblyDurationMs={}",
                state.phase(), state.sessionId(), context.totalTokens(), durationMs);

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
