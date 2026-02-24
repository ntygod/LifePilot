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

    /** 系统提示词区固定比例。 */
    private static final float SYSTEM_PROMPT_RATIO = 0.10f;

    /** 用户消息区固定比例。 */
    private static final float USER_MESSAGE_RATIO = 0.15f;

    private final MemoryProperties properties;

    public TokenBudgetAllocator(MemoryProperties properties) {
        this.properties = properties;
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
        int systemPromptBudget = Math.round(contextWindowSize * SYSTEM_PROMPT_RATIO);
        int userMessageBudget = Math.round(contextWindowSize * USER_MESSAGE_RATIO);

        // 剩余 75% 在工作记忆区和检索上下文区之间动态分配
        int remaining = contextWindowSize - systemPromptBudget - userMessageBudget;

        float workingMemoryRatio;
        float retrievalRatio;

        if (topRetrievalScore > 0.9f) {
            // 高相关度检索结果：检索区扩展到 35%，工作记忆压缩到 40%
            workingMemoryRatio = 40.0f / 75.0f;
            retrievalRatio = 35.0f / 75.0f;
        } else if (conversationTurns > 10) {
            // 长对话：工作记忆扩展到 60%，检索压缩到 15%（原文 20% 归一化到 75%）
            workingMemoryRatio = 60.0f / 75.0f;
            retrievalRatio = 15.0f / 75.0f;
        } else {
            // 默认：工作记忆 50%，检索 25%
            workingMemoryRatio = 50.0f / 75.0f;
            retrievalRatio = 25.0f / 75.0f;
        }

        int workingMemoryBudget = Math.round(remaining * workingMemoryRatio);
        int retrievalBudget = Math.round(remaining * retrievalRatio);

        log.debug("Token 预算分配: 总窗口={}, 系统提示词={}, 用户消息={}, 工作记忆={}, 检索={}",
                contextWindowSize, systemPromptBudget, userMessageBudget, workingMemoryBudget, retrievalBudget);

        return new BudgetAllocation(systemPromptBudget, workingMemoryBudget, retrievalBudget, userMessageBudget, contextWindowSize);
    }
}
