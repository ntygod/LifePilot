package com.lifepilot.agent.context;

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
        int system = totalTokens * 15 / 100;
        int history = totalTokens * 30 / 100;
        int memory = totalTokens * 35 / 100;
        int toolSchema = totalTokens * 10 / 100;
        int toolResult = 0;
        int reserved = totalTokens - system - history - memory - toolSchema - toolResult;

        return new TokenBudget(
                system, history, memory, toolSchema, toolResult, reserved,
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
