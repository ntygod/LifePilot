package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;

/**
 * Token 预算分配与消耗记录。
 *
 * <p>将总 Token 预算按预定义比例分配到六个槽位，
 * 并跟踪各槽位的实际消耗。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public record TokenBudget(
        int systemPromptBudget,
        int historyBudget,
        int memoryBudget,
        int toolSchemaBudget,
        int toolResultBudget,
        int reservedBuffer,
        int systemPromptUsed,
        int historyUsed,
        int memoryUsed,
        int toolSchemaUsed,
        int toolResultUsed
) {

    /**
     * ReAct 架构默认预算分配（使用广泛检索比例）。
     *
     * <p>分配比例：System 15% / History 30% / Memory 35% / ToolSchema 10% / ToolResult 0% / Reserved 10%</p>
     *
     * @param totalTokens 总 Token 预算
     * @return 分配后的 TokenBudget
     */
    public static TokenBudget allocateDefault(int totalTokens) {
        return allocateDefault(totalTokens, new AgentConfigProperties.ContextConfig.TokenAllocation());
    }

    /**
     * 按配置比例分配上下文 Token 预算。
     */
    public static TokenBudget allocateDefault(int totalTokens,
                                              AgentConfigProperties.ContextConfig.TokenAllocation allocation) {
        AgentConfigProperties.ContextConfig.TokenAllocation safeAllocation =
                allocation != null ? allocation : new AgentConfigProperties.ContextConfig.TokenAllocation();
        int[] weights = new int[]{
                Math.max(0, safeAllocation.getSystemPromptPercent()),
                Math.max(0, safeAllocation.getHistoryPercent()),
                Math.max(0, safeAllocation.getMemoryPercent()),
                Math.max(0, safeAllocation.getToolSchemaPercent()),
                Math.max(0, safeAllocation.getToolResultPercent()),
                Math.max(0, safeAllocation.getReservedBufferPercent())
        };
        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            weights = new int[]{15, 30, 25, 10, 10, 10};
            totalWeight = 100;
        }

        int[] budgets = new int[weights.length];
        int remaining = Math.max(0, totalTokens);
        for (int i = 0; i < weights.length; i++) {
            budgets[i] = i == weights.length - 1
                    ? remaining
                    : Math.max(0, totalTokens * weights[i] / totalWeight);
            remaining -= budgets[i];
        }

        return new TokenBudget(
                budgets[0], budgets[1], budgets[2], budgets[3], budgets[4], budgets[5],
                0, 0, 0, 0, 0
        );
    }

    /** 所有槽位预算之和。 */
    public int totalBudget() {
        return systemPromptBudget + historyBudget + memoryBudget
                + toolSchemaBudget + toolResultBudget + reservedBuffer;
    }

    /** 所有槽位实际消耗之和。 */
    public int totalConsumed() {
        return systemPromptUsed + historyUsed + memoryUsed
                + toolSchemaUsed + toolResultUsed;
    }

    /** 当 totalConsumed 超过 totalBudget - reservedBuffer 时返回 true。 */
    public boolean isOverBudget() {
        return totalConsumed() > totalBudget() - reservedBuffer;
    }
}
