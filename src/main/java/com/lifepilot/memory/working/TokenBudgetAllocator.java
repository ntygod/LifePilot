package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 预算分配器 — 根据上下文窗口大小和会话状态动态分配四区域预算。
 *
 * <p>分配策略：
 * <ul>
 *   <li>系统提示词区：固定 10%</li>
 *   <li>用户消息区：固定 15%</li>
 *   <li>剩余 75% 在工作记忆区和检索上下文区之间动态分配</li>
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
     * @param contextWindowSize 上下文窗口总 Token 数
     * @param conversationTurns 当前对话轮次数
     * @param topRetrievalScore 最高检索相关度评分 [0.0, 1.0]
     * @return 四区域预算分配结果
     */
    public BudgetAllocation allocate(int contextWindowSize, int conversationTurns, float topRetrievalScore) {
        int systemPromptBudget = Math.round(contextWindowSize * budgetConfig.getSystemPromptRatio());
        int userMessageBudget = Math.round(contextWindowSize * budgetConfig.getUserMessageRatio());

        // 剩余部分在工作记忆区和检索上下文区之间动态分配
        int remaining = contextWindowSize - systemPromptBudget - userMessageBudget;

        // 归一化基数 = (1 - 固定比例之和) × 100
        float remainingBase = (1.0f - budgetConfig.getSystemPromptRatio() - budgetConfig.getUserMessageRatio()) * 100.0f;

        float workingMemoryRatio;
        float retrievalRatio;

        if (topRetrievalScore > budgetConfig.getHighRelevanceThreshold()) {
            // 高相关度检索结果
            workingMemoryRatio = budgetConfig.getHighRelevanceWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getHighRelevanceRetrieval() / remainingBase;
        } else if (conversationTurns > budgetConfig.getLongConversationTurnsThreshold()) {
            // 长对话
            workingMemoryRatio = budgetConfig.getLongConversationWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getLongConversationRetrieval() / remainingBase;
        } else {
            // 默认场景
            workingMemoryRatio = budgetConfig.getDefaultWorkingMemory() / remainingBase;
            retrievalRatio = budgetConfig.getDefaultRetrieval() / remainingBase;
        }

        int workingMemoryBudget = Math.round(remaining * workingMemoryRatio);
        int retrievalBudget = Math.round(remaining * retrievalRatio);

        log.debug("Token 预算分配: 总窗口={}, 系统提示词={}, 用户消息={}, 工作记忆={}, 检索={}",
                contextWindowSize, systemPromptBudget, userMessageBudget, workingMemoryBudget, retrievalBudget);

        return new BudgetAllocation(systemPromptBudget, workingMemoryBudget, retrievalBudget, userMessageBudget, contextWindowSize);
    }
}
