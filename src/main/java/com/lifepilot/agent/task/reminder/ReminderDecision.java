package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 提醒决策结果。
 *
 * @param candidate        候选
 * @param action           决策动作
 * @param nextEvaluationAt 下次评估时间
 * @param reason           决策原因
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderDecision(
        ReminderCandidate candidate,
        ReminderAction action,
        @Nullable Instant nextEvaluationAt,
        String reason
) {

    public ReminderDecision {
        candidate = Objects.requireNonNull(candidate, "candidate 不能为空");
        action = Objects.requireNonNull(action, "action 不能为空");
        reason = Objects.requireNonNullElse(reason, "");
    }

    public float finalScore() {
        return candidate.finalScore();
    }
}
