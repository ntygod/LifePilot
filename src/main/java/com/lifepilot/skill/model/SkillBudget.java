package com.lifepilot.skill.model;

import com.lifepilot.agent.model.Budget;

import java.time.Duration;

/**
 * Skill 独立预算约束。
 *
 * <p>定义 Skill 激活时的 Token、步骤、时间和成本预算上限，
 * 通过 {@link #toAgentBudget()} 转换为 Agent 引擎的 {@link Budget} 对象。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public record SkillBudget(int maxTokens, int maxSteps, int timeoutSeconds, int maxCostCents) {

    /** 默认预算：8000 Token、10 步、120 秒、50 分。 */
    public static final SkillBudget DEFAULT = new SkillBudget(8000, 10, 120, 50);

    /** 轻量预算：2000 Token、5 步、30 秒、10 分。 */
    public static final SkillBudget LIGHTWEIGHT = new SkillBudget(2000, 5, 30, 10);

    /** 重量预算：20000 Token、20 步、300 秒、200 分。 */
    public static final SkillBudget HEAVYWEIGHT = new SkillBudget(20000, 20, 300, 200);

    /**
     * 转换为 Agent 引擎的 Budget 对象。
     *
     * @return 对应的 {@link Budget} 实例
     */
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
