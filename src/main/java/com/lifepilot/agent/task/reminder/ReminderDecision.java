package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 提醒决策结果。
 *
 * @param candidate        候选
 * @param action           决策动作
 * @param skipReason       跳过原因枚举（仅 SKIP 动作时有值）
 * @param nextEvaluationAt 下次评估时间
 * @param reason           面向展示的决策原因文案
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderDecision(
        ReminderCandidate candidate,
        ReminderAction action,
        @Nullable ReminderSkipReason skipReason,
        @Nullable Instant nextEvaluationAt,
        String reason
) {

    /**
     * 便捷构造：非 SKIP 动作或无需指定结构化跳过原因时使用。
     */
    public ReminderDecision(ReminderCandidate candidate,
                            ReminderAction action,
                            @Nullable Instant nextEvaluationAt,
                            String reason) {
        this(candidate, action, null, nextEvaluationAt, reason);
    }

    public ReminderDecision {
        candidate = Objects.requireNonNull(candidate, "candidate 不能为空");
        action = Objects.requireNonNull(action, "action 不能为空");
        reason = Objects.requireNonNullElse(reason, "");
    }

    public float finalScore() {
        return candidate.finalScore();
    }
}
