package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 预算分配器 — 根据上下文窗口大小和会话状态动态分配九区域预算。
 *
 * <p>分配策略：
 * <ul>
 *   <li>系统提示词区：固定 10%</li>
 *   <li>用户消息区：固定 15%</li>
 *   <li>剩余 75% 在六个记忆区域之间独立分配，每个区域不超过配置的 max 上限</li>
 *   <li>超出总预算时按优先级从低到高截断：知识库 &lt; 操作模板 &lt; 知识实体 &lt; 跨会话摘要 &lt; 用户画像 &lt; 当前会话历史</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TokenBudgetAllocator {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetAllocator.class);

    private final MemoryProperties.TokenBudget budgetConfig;
    private final MemoryProperties.Retrieval retrievalConfig;

    public TokenBudgetAllocator(MemoryProperties properties) {
        this.budgetConfig = properties.getTokenBudget();
        this.retrievalConfig = properties.getRetrieval();
    }

    /**
     * 根据上下文窗口大小和会话状态分配 Token 预算。
     *
     * <p>固定区域（系统提示词、用户消息）按比例分配，
     * 剩余部分在六个记忆区域之间独立分配，每个区域不超过其配置的 max 上限。
     * 当六区域总和超过剩余预算时，按优先级从低到高截断。</p>
     *
     * <p>当 {@code hasMemoryData=false} 时，记忆区域预算归零，
     * 释放的预算重新分配给用户消息和当前会话区域。</p>
     *
     * @param contextWindowSize 上下文窗口总 Token 数
     * @param conversationTurns 当前对话轮次数
     * @param topRetrievalScore 最高检索相关度评分 [0.0, 1.0]
     * @param hasMemoryData 是否有记忆数据可用（检索结果非空）
     * @return 九区域预算分配结果
     */
    public BudgetAllocation allocate(int contextWindowSize, int conversationTurns, float topRetrievalScore, boolean hasMemoryData) {
        int systemPromptBudget = Math.round(contextWindowSize * budgetConfig.getSystemPromptRatio());
        int userMessageBudget = Math.round(contextWindowSize * budgetConfig.getUserMessageRatio());

        // 无记忆数据时：记忆区域预算归零，释放预算重新分配给用户消息和当前会话
        if (!hasMemoryData) {
            int released = Math.max(0, contextWindowSize - systemPromptBudget - userMessageBudget);
            int extraUserMessage = released / 3;
            int extraCurrentSession = released - extraUserMessage;
            log.debug("Token 预算分配（无记忆数据）: 总窗口={}, 系统提示词={}, 用户消息={}, 当前会话={}",
                    contextWindowSize, systemPromptBudget, userMessageBudget + extraUserMessage, extraCurrentSession);
            return new BudgetAllocation(
                    0,  // userProfileBudget
                    extraCurrentSession,  // currentSessionBudget
                    0,  // crossSessionBudget
                    0,  // knowledgeEntityBudget
                    0,  // proceduralBudget
                    0,  // knowledgeBaseBudget
                    systemPromptBudget,
                    userMessageBudget + extraUserMessage,
                    contextWindowSize);
        }

        // 剩余部分在六个记忆区域之间独立分配
        int remaining = Math.max(0, contextWindowSize - systemPromptBudget - userMessageBudget);

        // 计算场景权重因子，影响当前会话和知识实体的比例分配
        float sessionWeight = computeSessionWeight(conversationTurns, topRetrievalScore);
        float retrievalWeight = computeRetrievalWeight(conversationTurns, topRetrievalScore);

        // 六区域初始分配：按比例分配但不超过各自 max 上限
        int[] budgets = computeInitialBudgets(remaining, sessionWeight, retrievalWeight);

        // 低质量检索时缩减知识实体预算，释放给当前会话
        if (topRetrievalScore > 0 && topRetrievalScore < retrievalConfig.getLowQualityScoreThreshold()) {
            int originalKnowledge = budgets[IDX_KNOWLEDGE_ENTITY];
            budgets[IDX_KNOWLEDGE_ENTITY] = Math.round(originalKnowledge * retrievalConfig.getLowQualityBudgetRatio());
            int released = originalKnowledge - budgets[IDX_KNOWLEDGE_ENTITY];
            // 释放预算给当前会话，但不超过 max 上限
            budgets[IDX_CURRENT_SESSION] = Math.min(
                    budgetConfig.getCurrentSessionMax(),
                    budgets[IDX_CURRENT_SESSION] + released);
            log.debug("低质量检索预算缩减: topScore={}, 知识实体 {}→{}, 释放 {} 给当前会话",
                    topRetrievalScore, originalKnowledge, budgets[IDX_KNOWLEDGE_ENTITY], released);
        }

        // 优先级截断：当六区域总和超过 remaining 时，按优先级从低到高截断
        truncateByPriority(budgets, remaining);

        log.debug("Token 预算分配: 总窗口={}, 系统提示词={}, 用户消息={}, "
                        + "用户画像={}, 当前会话={}, 跨会话={}, 知识实体={}, 操作模板={}, 知识库={}",
                contextWindowSize, systemPromptBudget, userMessageBudget,
                budgets[IDX_USER_PROFILE], budgets[IDX_CURRENT_SESSION],
                budgets[IDX_CROSS_SESSION], budgets[IDX_KNOWLEDGE_ENTITY],
                budgets[IDX_PROCEDURAL], budgets[IDX_KNOWLEDGE_BASE]);

        return new BudgetAllocation(
                budgets[IDX_USER_PROFILE], budgets[IDX_CURRENT_SESSION],
                budgets[IDX_CROSS_SESSION], budgets[IDX_KNOWLEDGE_ENTITY],
                budgets[IDX_PROCEDURAL], budgets[IDX_KNOWLEDGE_BASE],
                systemPromptBudget, userMessageBudget, contextWindowSize);
    }

    // ── 六区域索引（按优先级从高到低排列）──

    /** 当前会话历史（最高优先级，最后截断）。 */
    private static final int IDX_CURRENT_SESSION = 0;
    /** 用户画像。 */
    private static final int IDX_USER_PROFILE = 1;
    /** 跨会话摘要。 */
    private static final int IDX_CROSS_SESSION = 2;
    /** 相关知识实体。 */
    private static final int IDX_KNOWLEDGE_ENTITY = 3;
    /** 操作模板。 */
    private static final int IDX_PROCEDURAL = 4;
    /** 知识库片段（最低优先级，最先截断）。 */
    private static final int IDX_KNOWLEDGE_BASE = 5;

    /** 截断顺序：从最低优先级到最高优先级。 */
    private static final int[] TRUNCATION_ORDER = {
            IDX_KNOWLEDGE_BASE, IDX_PROCEDURAL, IDX_KNOWLEDGE_ENTITY,
            IDX_CROSS_SESSION, IDX_USER_PROFILE, IDX_CURRENT_SESSION
    };

    /**
     * 计算当前会话历史的权重因子。
     * 长对话时增大当前会话权重，高检索相关度时适当降低。
     */
    private float computeSessionWeight(int conversationTurns, float topRetrievalScore) {
        if (conversationTurns > budgetConfig.getLongConversationTurnsThreshold()) {
            return budgetConfig.getLongConversationWorkingMemory() / 100.0f;
        } else if (topRetrievalScore > budgetConfig.getHighRelevanceThreshold()) {
            return budgetConfig.getHighRelevanceWorkingMemory() / 100.0f;
        }
        return budgetConfig.getDefaultWorkingMemory() / 100.0f;
    }

    /**
     * 计算知识实体检索的权重因子。
     * 高检索相关度时增大检索权重，长对话时适当降低。
     */
    private float computeRetrievalWeight(int conversationTurns, float topRetrievalScore) {
        if (topRetrievalScore > budgetConfig.getHighRelevanceThreshold()) {
            return budgetConfig.getHighRelevanceRetrieval() / 100.0f;
        } else if (conversationTurns > budgetConfig.getLongConversationTurnsThreshold()) {
            return budgetConfig.getLongConversationRetrieval() / 100.0f;
        }
        return budgetConfig.getDefaultRetrieval() / 100.0f;
    }

    /**
     * 计算六区域初始预算，每个区域不超过其配置的 max 上限。
     *
     * <p>当前会话和知识实体按场景权重分配比例，其余四个区域按 max 上限的比例分配剩余空间。</p>
     *
     * @return 六元素数组，索引对应 IDX_* 常量
     */
    private int[] computeInitialBudgets(int remaining, float sessionWeight, float retrievalWeight) {
        int[] budgets = new int[6];

        // 当前会话历史：按场景权重分配，不超过 max
        budgets[IDX_CURRENT_SESSION] = Math.min(
                budgetConfig.getCurrentSessionMax(),
                Math.round(remaining * sessionWeight));

        // 知识实体：按场景权重分配，不超过 max
        budgets[IDX_KNOWLEDGE_ENTITY] = Math.min(
                budgetConfig.getKnowledgeEntityMax(),
                Math.round(remaining * retrievalWeight));

        // 其余四个区域：按各自 max 占总 max 的比例分配剩余空间，不超过各自 max
        int usedByDynamic = budgets[IDX_CURRENT_SESSION] + budgets[IDX_KNOWLEDGE_ENTITY];
        int remainingForOthers = Math.max(0, remaining - usedByDynamic);

        int totalOtherMax = budgetConfig.getUserProfileMax() + budgetConfig.getCrossSessionMax()
                + budgetConfig.getProceduralMax() + budgetConfig.getKnowledgeBaseMax();

        if (totalOtherMax > 0 && remainingForOthers > 0) {
            budgets[IDX_USER_PROFILE] = Math.min(
                    budgetConfig.getUserProfileMax(),
                    Math.round((float) remainingForOthers * budgetConfig.getUserProfileMax() / totalOtherMax));
            budgets[IDX_CROSS_SESSION] = Math.min(
                    budgetConfig.getCrossSessionMax(),
                    Math.round((float) remainingForOthers * budgetConfig.getCrossSessionMax() / totalOtherMax));
            budgets[IDX_PROCEDURAL] = Math.min(
                    budgetConfig.getProceduralMax(),
                    Math.round((float) remainingForOthers * budgetConfig.getProceduralMax() / totalOtherMax));
            budgets[IDX_KNOWLEDGE_BASE] = Math.min(
                    budgetConfig.getKnowledgeBaseMax(),
                    Math.round((float) remainingForOthers * budgetConfig.getKnowledgeBaseMax() / totalOtherMax));
        }

        return budgets;
    }

    /**
     * 优先级截断：当六区域总和超过 remaining 时，按优先级从低到高依次截断。
     *
     * <p>截断顺序（最先截断 → 最后截断）：
     * 知识库片段 → 操作模板 → 知识实体 → 跨会话摘要 → 用户画像 → 当前会话历史</p>
     */
    private void truncateByPriority(int[] budgets, int remaining) {
        int total = 0;
        for (int b : budgets) {
            total += b;
        }
        if (total <= remaining) {
            return;
        }

        int excess = total - remaining;
        for (int idx : TRUNCATION_ORDER) {
            if (excess <= 0) break;
            int cut = Math.min(budgets[idx], excess);
            budgets[idx] -= cut;
            excess -= cut;
        }
    }
}
