package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 提醒决策输出。
 *
 * @param decision    最终决策
 * @param policyTrace 学习策略追踪
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderDecisionOutcome(
        ReminderDecision decision,
        @Nullable ReminderPolicyTrace policyTrace
) {

    public ReminderDecisionOutcome {
        decision = Objects.requireNonNull(decision, "decision 不能为空");
    }
}
