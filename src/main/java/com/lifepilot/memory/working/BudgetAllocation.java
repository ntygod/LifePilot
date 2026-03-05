package com.lifepilot.memory.working;

/**
 * Token 预算分配结果 — 记录 LLM 上下文窗口的九区域预算分配。
 *
 * <p>六个记忆区域按优先级从高到低：
 * <ol>
 *   <li>用户画像（userProfileBudget）</li>
 *   <li>当前会话历史 L1（currentSessionBudget）</li>
 *   <li>跨会话摘要 L2（crossSessionBudget）</li>
 *   <li>相关知识实体 L3（knowledgeEntityBudget）</li>
 *   <li>操作模板 L4（proceduralBudget）</li>
 *   <li>知识库片段（knowledgeBaseBudget）</li>
 * </ol>
 * 另有系统提示词（systemPromptBudget）和用户消息区（userMessageBudget）为固定区域。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public record BudgetAllocation(
        int userProfileBudget,
        int currentSessionBudget,
        int crossSessionBudget,
        int knowledgeEntityBudget,
        int proceduralBudget,
        int knowledgeBaseBudget,
        int systemPromptBudget,
        int userMessageBudget,
        int totalBudget
) {

    /** 验证分配总和不超过总预算。 */
    public BudgetAllocation {
        int sum = userProfileBudget + currentSessionBudget + crossSessionBudget
                + knowledgeEntityBudget + proceduralBudget + knowledgeBaseBudget
                + systemPromptBudget + userMessageBudget;
        if (sum > totalBudget) {
            throw new IllegalArgumentException(
                    "预算分配总和 %d 超过总预算 %d".formatted(sum, totalBudget));
        }
    }
}
