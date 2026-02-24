package com.lifepilot.agent.context;

import com.lifepilot.agent.model.AgentPhase;

/**
 * Token 预算分配与消耗记录。
 *
 * <p>根据 AgentPhase 按预定义比例将总 Token 预算分配到六个槽位，
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
     * 根据阶段和总 Token 数按比例创建预算分配。
     *
     * <p>分配比例（System / History / Memory / ToolSchema / ToolResult / Reserved）：
     * <ul>
     *   <li>UNDERSTANDING: 15% / 30% / 35% / 10% / 0% / 10%</li>
     *   <li>PLANNING:      15% / 20% / 15% / 35% / 5% / 10%</li>
     *   <li>EXECUTING:     10% / 10% / 5%  / 40% / 25% / 10%</li>
     *   <li>REFLECTING:    10% / 15% / 10% / 5%  / 50% / 10%</li>
     *   <li>RESPONDING:    15% / 25% / 20% / 0%  / 30% / 10%</li>
     * </ul>
     *
     * @param phase       当前 AgentPhase（不可为 TERMINATED）
     * @param totalTokens 总 Token 预算
     * @return 分配后的 TokenBudget
     */
    public static TokenBudget allocate(AgentPhase phase, int totalTokens) {
        // 比例数组：[system, history, memory, toolSchema, toolResult, reserved]
        int[] percentages = switch (phase) {
            case UNDERSTANDING -> new int[]{15, 30, 35, 10, 0, 10};
            case PLANNING      -> new int[]{15, 20, 15, 35, 5, 10};
            case EXECUTING     -> new int[]{10, 10, 5, 40, 25, 10};
            case REFLECTING    -> new int[]{10, 15, 10, 5, 50, 10};
            case RESPONDING    -> new int[]{15, 25, 20, 0, 30, 10};
            case TERMINATED    -> new int[]{0, 0, 0, 0, 0, 0};
        };

        int system = totalTokens * percentages[0] / 100;
        int history = totalTokens * percentages[1] / 100;
        int memory = totalTokens * percentages[2] / 100;
        int toolSchema = totalTokens * percentages[3] / 100;
        int toolResult = totalTokens * percentages[4] / 100;
        // reserved 取剩余部分，避免整数截断导致总和不等于 totalTokens
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
