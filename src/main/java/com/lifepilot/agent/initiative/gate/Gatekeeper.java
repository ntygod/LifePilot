package com.lifepilot.agent.initiative.gate;

import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

/**
 * 表达门控 — 判断"现在是否适合表达这个想法"。
 *
 * <p>纯规则硬约束，不用 LLM。只判断时机，不判断想法价值。</p>
 *
 * <p>门控规则（按优先级）：
 * <ol>
 *   <li>静默时段 → Wait（除非 CRITICAL）</li>
 *   <li>每日额度已满 → Wait（除非 CRITICAL）</li>
 *   <li>用户正在对话中 → Wait</li>
 *   <li>间隔太短 → Wait（除非 CRITICAL）</li>
 *   <li>任务边界窗口 → 优先 Express</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class Gatekeeper {

    private final int dailyMaxExpressions;
    private final Duration minInterval;
    private final LocalTime quietHoursStart;
    private final LocalTime quietHoursEnd;
    private final Clock clock;

    public Gatekeeper(int dailyMaxExpressions,
                      Duration minInterval,
                      LocalTime quietHoursStart,
                      LocalTime quietHoursEnd,
                      Clock clock) {
        if (dailyMaxExpressions < 0) {
            throw new IllegalArgumentException("每日主动表达上限不能小于 0");
        }
        if (minInterval.isNegative()) {
            throw new IllegalArgumentException("主动表达最小间隔不能为负数");
        }
        this.dailyMaxExpressions = dailyMaxExpressions;
        this.minInterval = minInterval;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
        this.clock = clock;
    }

    /**
     * 评估想法是否适合现在表达。
     */
    public Decision evaluate(Thought thought, GatekeeperContext context) {
        boolean critical = isCritical(thought);

        // 1. 静默时段
        if (isQuietHours() && !critical) {
            return new Decision.Wait(thought, "静默时段", Duration.ofHours(1));
        }

        // 2. 每日额度
        if (context.todayExpressedCount() >= dailyMaxExpressions && !critical) {
            return new Decision.Wait(thought, "今日额度已满", Duration.ofHours(6));
        }

        // 3. 用户正在对话中
        if (context.userInConversation()) {
            return new Decision.Wait(thought, "用户正在对话中", Duration.ofMinutes(5));
        }

        // 4. 间隔太短
        if (context.timeSinceLastExpress() != null
                && context.timeSinceLastExpress().compareTo(minInterval) < 0
                && !critical) {
            Duration remaining = minInterval.minus(context.timeSinceLastExpress());
            return new Decision.Wait(thought, "距上次表达间隔太短", remaining);
        }

        // 5. 通过所有门控 → Express
        return new Decision.Express(thought, resolveUrgency(thought, critical));
    }

    private boolean isQuietHours() {
        LocalTime now = LocalTime.now(clock);
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !now.isBefore(quietHoursStart) && now.isBefore(quietHoursEnd);
        }
        // 跨午夜：如 23:00 - 08:00
        return !now.isBefore(quietHoursStart) || now.isBefore(quietHoursEnd);
    }

    private boolean isCritical(Thought thought) {
        if (thought.kind() != ThoughtKind.REMINDER || thought.matureAt() == null) {
            return false;
        }
        Duration untilDeadline = Duration.between(Instant.now(clock), thought.matureAt());
        return untilDeadline.compareTo(Duration.ofHours(2)) <= 0;
    }

    private ExpressUrgency resolveUrgency(Thought thought, boolean critical) {
        if (critical) {
            return ExpressUrgency.CRITICAL;
        }
        if (thought.maturity() >= 0.9f && thought.kind() == ThoughtKind.REMINDER) {
            return ExpressUrgency.HIGH;
        }
        if (thought.maturity() >= 0.8f) {
            return ExpressUrgency.NORMAL;
        }
        return ExpressUrgency.LOW;
    }

    public enum ExpressUrgency { LOW, NORMAL, HIGH, CRITICAL }

    public sealed interface Decision permits Decision.Express, Decision.Wait, Decision.Dismiss {
        record Express(Thought thought, ExpressUrgency urgency) implements Decision {}
        record Wait(Thought thought, String reason, Duration suggestedDelay) implements Decision {}
        record Dismiss(Thought thought, String reason) implements Decision {}
    }

    public record GatekeeperContext(
        boolean userInConversation,
        int todayExpressedCount,
        @Nullable Duration timeSinceLastExpress
    ) {}
}
