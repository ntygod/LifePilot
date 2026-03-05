package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.StepRecord;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.working.*;
import com.lifepilot.observability.redactor.DataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
    // 知识库（文档）检索：可选注入，未启用时不影响主流程
    @Nullable private final DocumentRetriever documentRetriever;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final DocumentRepository documentRepository;

    /** 基础版构造器（向后兼容，记忆字段为 null）。 */
    public ContextAssembler(AgentConfigProperties config) {
        this.config = config;
        this.hybridRetriever = null;
        this.workingMemory = null;
        this.tokenBudgetAllocator = null;
        this.retrievalStrategy = null;
        this.dataRedactor = null;
        this.documentRetriever = null;
        this.sessionKnowledgeBaseRepository = null;
        this.documentRepository = null;
    }

    /** 完整版构造器（注入记忆系统依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            HybridRetriever hybridRetriever,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            MemoryRetrievalStrategy retrievalStrategy,
                            @Nullable DataRedactor dataRedactor) {
        this(config, hybridRetriever, workingMemory, tokenBudgetAllocator, retrievalStrategy, dataRedactor,
                null, null, null);
    }

    /** 完整版构造器（注入记忆系统 + 可选知识库检索依赖）。 */
    public ContextAssembler(AgentConfigProperties config,
                            HybridRetriever hybridRetriever,
                            WorkingMemory workingMemory,
                            TokenBudgetAllocator tokenBudgetAllocator,
                            MemoryRetrievalStrategy retrievalStrategy,
                            @Nullable DataRedactor dataRedactor,
                            @Nullable DocumentRetriever documentRetriever,
                            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                            @Nullable DocumentRepository documentRepository) {
        this.config = config;
        this.hybridRetriever = hybridRetriever;
        this.workingMemory = workingMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.retrievalStrategy = retrievalStrategy;
        this.dataRedactor = dataRedactor;
        this.documentRetriever = documentRetriever;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.documentRepository = documentRepository;
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
            var retrievalResults = safeRetrieve(hybridRetriever, state.goal(), strategyConfig);
            // 2.1 知识库检索（可选；仅当会话关联了 knowledgeBaseIds）
            var kbSnippets = safeRetrieveKnowledgeBaseSnippets(state.sessionId(), state.goal(), 5);
            // L4: 可选意图匹配提示（来自 HybridRetriever 内部的 IntentMatcher 结果）
            var procedureHintSlot = safeGetLastProcedureSlot(hybridRetriever);
            int retrievalCount = retrievalResults.size();
            float topScore = retrievalResults.isEmpty() ? 0.0f
                    : retrievalResults.getFirst().fusedScore();
            if (retrievalResults.isEmpty() && state.goal() != null) {
                // 检索返回空可能是降级
                degraded = true;
            }

            // 3. 获取会话槽位（降级容错）
            var slots = safeGetContext(workingMemory, state.sessionId());

            // 4. 动态预算分配（降级容错）
            int conversationTurns = countConversationTurns(slots);
            var budgetAllocation = safeAllocate(tokenBudgetAllocator, conversationTurns, topScore);

            // 5. 按预算截断
            var truncatedMemories = truncateByBudget(retrievalResults, budgetAllocation.knowledgeEntityBudget());
            var truncatedSlots = truncateSlotsByBudget(slots, budgetAllocation.currentSessionBudget());

            // 6. 将检索上下文注入 L1（ReasoningSlot），并格式化检索结果
            slots = injectRetrievalReasoningSlots(workingMemory, state.sessionId(), truncatedMemories, procedureHintSlot, slots);
            var formattedMemories = formatRetrievalResults(truncatedMemories);
            int workingMemoryTokens = truncatedSlots.stream()
                    .mapToInt(WorkingMemorySlot::tokenCount).sum();

            // 7. 构建 TokenBudget
            var tokenBudget = buildTokenBudget(state.phase(), budgetAllocation,
                    formattedMemories, truncatedSlots);

            // 8. 构建 Prompt
            String systemPrompt = buildSystemPrompt(state.phase());
            String userPrompt = buildEnhancedUserPrompt(state, formattedMemories, kbSnippets, truncatedSlots);

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

    /** 安全执行预算分配，异常时使用静态分配降级。 */
    private BudgetAllocation safeAllocate(TokenBudgetAllocator allocator, int conversationTurns, float topScore) {
        try {
            int windowSize = config.getContext().getMaxContextTokens();
            return allocator.allocate(windowSize, conversationTurns, topScore);
        } catch (Exception e) {
            log.warn("预算分配降级: error={}", e.getMessage());
            int total = config.getContext().getMaxContextTokens();
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
    private List<WorkingMemorySlot> injectRetrievalReasoningSlots(WorkingMemory memory,
                                                                  String sessionId,
                                                                  List<RetrievalResult> truncatedMemories,
                                                                  Optional<ReasoningSlot> procedureHintSlot,
                                                                  List<WorkingMemorySlot> existingSlots) {
        if (sessionId == null || sessionId.isBlank()) {
            return existingSlots;
        }
        var updated = new ArrayList<>(existingSlots != null ? existingSlots : List.of());
        try {
            // 1) L4 程序提示 → ReasoningSlot（若尚未存在）
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

            // 2) 检索到的语义记忆 → ReasoningSlot（只注入前若干条，避免污染 L1）
            int maxInjected = Math.min(5, truncatedMemories.size());
            for (int i = 0; i < maxInjected; i++) {
                RetrievalResult result = truncatedMemories.get(i);
                String thought = formatSingleResult(result);
                if (thought == null || thought.isBlank()) {
                    continue;
                }
                ReasoningSlot reasoningSlot = ReasoningSlot.retrievalContext(thought, estimateTokens(thought));
                memory.append(sessionId, reasoningSlot);
                updated.add(reasoningSlot);
            }
        } catch (Exception e) {
            log.warn("检索上下文注入 L1 失败: sessionId={}, error={}", sessionId, e.getMessage());
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
     * 构建阶段专用 System Prompt。
     *
     * @param phase 当前阶段
     * @return System Prompt 文本
     */
    String buildSystemPrompt(AgentPhase phase) {
        String roleDefinition = "你是 LifePilot，一个智能个人助手，专注于理解用户意图并高效完成任务。";
        String phaseInstruction = switch (phase) {
            case UNDERSTANDING -> """
                    阶段：意图理解
                    
                    任务：
                    1. 深度分析用户输入的语义和意图
                    2. 提取关键实体：人名、地名、时间、主题、数字等
                    3. 评估复杂度：
                       - SIMPLE：单步查询、问候、简单确认、闲聊。标记为SIMPLE的请求会直接生成响应，不会进入规划和工具执行阶段
                       - MODERATE：需要2-3步操作、涉及多个工具
                       - COMPLEX：多步骤、需要规划、涉及复杂逻辑
                    4. 判断信息完整性：
                       - 信息充足：canProceed=true, needsClarification=false
                       - 信息不足：canProceed=false, needsClarification=true，提供具体澄清问题
                    
                    重要：对于简单问候（如"你好"、"hi"、"早上好"、"在吗"）和闲聊，必须标记为complexity="SIMPLE"，系统会直接生成友好响应，不会调用任何工具。
                    
                    输出格式（严格JSON，无Markdown标记）：
                    {
                      "summary": "意图摘要（1-2句话）",
                      "needsClarification": false,
                      "clarificationQuestion": null,
                      "canProceed": true,
                      "entities": ["实体1", "实体2"],
                      "complexity": "SIMPLE|MODERATE|COMPLEX"
                    }
                    
                    示例（简单问候）：
                    {"summary":"用户发送问候","needsClarification":false,"clarificationQuestion":null,"canProceed":true,"entities":[],"complexity":"SIMPLE"}
                    
                    示例（需要澄清）：
                    {"summary":"用户想查询但未指定内容","needsClarification":true,"clarificationQuestion":"你想查询什么信息？","canProceed":false,"entities":[],"complexity":"MODERATE"}
                    """;
            case PLANNING -> """
                    阶段：任务规划
                    
                    任务：
                    1. 基于意图理解结果，制定清晰的执行计划
                    2. 为每个步骤指定工具ID、参数和描述
                    3. 预估Token消耗，确保不超过预算
                    4. 提供规划理由，说明为什么选择这些步骤
                    
                    输出格式（严格JSON）：
                    {
                      "steps": [
                        {
                          "toolId": "工具ID",
                          "params": {"key": "value"},
                          "description": "步骤描述"
                        }
                      ],
                      "estimatedTokens": 1000,
                      "rationale": "规划理由"
                    }
                    """;
            case EXECUTING -> """
                    阶段：工具执行
                    
                    任务：
                    1. 严格按照规划步骤执行工具调用
                    2. 记录每步的执行结果和状态
                    3. 根据结果判断是否需要调整后续步骤
                    4. 如遇错误，记录错误信息并评估是否可恢复
                    """;
            case REFLECTING -> """
                    阶段：反思评估
                    
                    任务：
                    1. 评估执行结果是否完全满足用户意图
                    2. 识别执行中的问题和不足
                    3. 决定是否需要重新规划或调整策略
                    4. 生成执行摘要，总结关键信息
                    
                    输出格式（严格JSON）：
                    {
                      "satisfied": true,
                      "adjustmentPlan": "调整计划（如无则为null）",
                      "summary": "执行摘要",
                      "needsReplanning": false
                    }
                    """;
            case RESPONDING -> """
                    阶段：生成响应
                    
                    任务：
                    1. 基于执行结果生成清晰、有用的回复
                    2. 使用自然语言，避免技术术语
                    3. 提供相关的后续操作建议
                    4. 如执行失败，提供友好的错误说明和解决建议
                    
                    输出格式（严格JSON）：
                    {
                      "content": "响应内容",
                      "suggestions": ["建议1", "建议2"]
                    }
                    """;
            case TERMINATED -> "";
        };
        String constraint = "\n\n重要约束：\n- 只输出JSON对象，不要任何Markdown代码块标记\n- 不要输出解释文字或注释\n- JSON必须完整且有效";

        return roleDefinition + "\n\n" + phaseInstruction + constraint;
    }

    /**
     * 构建增强版 User Prompt（结构化内容区域）。
     * 顺序：用户请求 → 相关记忆 → 对话历史 → 工具结果 → 推理上下文 → 已执行步骤 → 预算剩余
     */
    String buildEnhancedUserPrompt(AgentState state,
                                   List<String> memories,
                                   List<String> knowledgeBaseSnippets,
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

        // 2.5 知识库片段（条件性区域）
        if (knowledgeBaseSnippets != null && !knowledgeBaseSnippets.isEmpty()) {
            sb.append("\n知识库片段:\n");
            for (var snippet : knowledgeBaseSnippets) {
                sb.append("  - ").append(snippet).append("\n");
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

        var plan = state.plan();
        if (plan != null) {
            sb.append("当前计划: ").append(plan.rationale()).append("\n");
            sb.append("计划进度: ").append(state.planStepIndex())
                    .append("/").append(plan.steps().size()).append("\n");
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
