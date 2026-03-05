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
 *   <li>剩余 75% 在六个记忆区域之间动态分配</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TokenBudgetAllocator {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetAllocator.class);

    private final MemoryProperties.TokenBudget budgetConfig;

    public TokenBudgetAllocator(MemoryProperties properties) {
        this.budgetConfig = properties.getTokenBudget();
    }

    /**
     * 根据上下文窗口大小和会话状态分配 Token 预算。
     *
     * <p>固定区域（系统提示词、用户消息）按比例分配，
     * 剩余部分在六个记忆区域之间按场景动态分配。</p>
     *
     * @param contextWindowSize 上下文窗口总 Token 数
     * @param conversationTurns 当前对话轮次数
     * @param topRetrievalScore 最高检索相关度评分 [0.0, 1.0]
     * @return 九区域预算分配结果
     */
    public BudgetAllocation allocate(int contextWindowSize, int conversationTurns, float topRetrievalScore) {
        int systemPromptBudget = Math.round(contextWindowSize * budgetConfig.getSystemPromptRatio());
        int userMessageBudget = Math.round(contextWindowSize * budgetConfig.getUserMessageRatio());

        // 剩余部分在六个记忆区域之间动态分配
        int remaining = contextWindowSize - systemPromptBudget - userMessageBudget;

        // 归一化基数 = (1 - 固定比例之和) × 100
        float remainingBase = (1.0f - budgetConfig.getSystemPromptRatio() - budgetConfig.getUserMessageRatio()) * 100.0f;

        float workingMemoryRatio;
        float retrievalRatio;

        if (topRetrievalScore > budgetConfig.getHighRelevanceThreshold()) {
            workingMemoryRatio = budgetConfig.getHighRelevanceWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getHighRelevanceRetrieval() / remainingBase;
        } else if (conversationTurns > budgetConfig.getLongConversationTurnsThreshold()) {
            workingMemoryRatio = budgetConfig.getLongConversationWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getLongConversationRetrieval() / remainingBase;
        } else {
            workingMemoryRatio = budgetConfig.getDefaultWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getDefaultRetrieval() / remainingBase;
        }

        // 原 workingMemoryBudget → currentSessionBudget，原 retrievalBudget → knowledgeEntityBudget
        int currentSessionBudget = Math.round(remaining * workingMemoryRatio);
        int knowledgeEntityBudget = Math.round(remaining * retrievalRatio);

        // 新增区域暂按配置上限与剩余空间取较小值分配（后续 task 1.3 会重构为完整的六区域独立分配）
        int userProfileBudget = Math.min(budgetConfig.getUserProfileMax(), remaining / 10);
        int crossSessionBudget = Math.min(budgetConfig.getCrossSessionMax(), remaining / 10);
        int proceduralBudget = Math.min(budgetConfig.getProceduralMax(), remaining / 10);
        int knowledgeBaseBudget = Math.min(budgetConfig.getKnowledgeBaseMax(), remaining / 10);

        log.debug("Token 预算分配: 总窗口={}, 系统提示词={}, 用户消息={}, 当前会话={}, 知识实体={}, 用户画像={}, 跨会话={}, 操作模板={}, 知识库={}",
                contextWindowSize, systemPromptBudget, userMessageBudget,
                currentSessionBudget, knowledgeEntityBudget,
                userProfileBudget, crossSessionBudget, proceduralBudget, knowledgeBaseBudget);

        return new BudgetAllocation(
                userProfileBudget, currentSessionBudget, crossSessionBudget,
                knowledgeEntityBudget, proceduralBudget, knowledgeBaseBudget,
                systemPromptBudget, userMessageBudget, contextWindowSize);
    }

}
