package com.lifepilot.multiagent.model;

import com.lifepilot.agent.model.Budget;

import java.time.Duration;

/**
 * Agent 独立预算 record。
 *
 * <p>包含 Token、步骤、超时三个维度，可转换为 AgentLoop 使用的 {@link Budget}。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public record AgentBudget(int maxTokens, int maxSteps, int timeoutSeconds) {

    /** 默认预算：16000 Token、15 步、180 秒。 */
    public static final AgentBudget DEFAULT = new AgentBudget(16000, 15, 180);

    /** 轻量预算：4000 Token、8 步、60 秒。 */
    public static final AgentBudget LIGHTWEIGHT = new AgentBudget(4000, 8, 60);

    /** 重量预算：32000 Token、25 步、300 秒。 */
    public static final AgentBudget HEAVYWEIGHT = new AgentBudget(32000, 25, 300);

    /** 转换为 AgentLoop 使用的 Budget record。 */
    public Budget toAgentBudget() {
        return Budget.builder()
                .maxTokens(maxTokens)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(maxSteps)
                .maxDuration(Duration.ofSeconds(timeoutSeconds))
                .elapsed(Duration.ZERO)
                .build();
    }
}
