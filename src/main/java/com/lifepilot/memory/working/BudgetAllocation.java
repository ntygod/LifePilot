package com.lifepilot.memory.working;

/**
 * Token 预算分配结果 — 记录 LLM 上下文窗口的四区域预算分配。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record BudgetAllocation(
        int systemPromptBudget,
        int workingMemoryBudget,
        int retrievalBudget,
        int userMessageBudget,
        int totalBudget
) {

    /** 验证分配总和不超过总预算。 */
    public BudgetAllocation {
        int sum = systemPromptBudget + workingMemoryBudget + retrievalBudget + userMessageBudget;
        if (sum > totalBudget) {
            throw new IllegalArgumentException(
                    "预算分配总和 %d 超过总预算 %d".formatted(sum, totalBudget));
        }
    }
}
