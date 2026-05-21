package com.lifepilot.agent.initiative.gate;

import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import org.springframework.lang.Nullable;

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
 *   <li>静默时段 → Wait</li>
 *   <li>每日额度已满 → Wait（除非 CRITICAL）</li>
 *   <li>用户正在对话中 → Wait</li>
 *   <li>间隔太短 → Wait</li>
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

    public Gatekeeper(int dailyMaxExpressions,
                      Duration minInterval,
                      LocalTime quietHoursStart,
                      LocalTime quietHoursEnd) {
        this.dailyMaxExpressions = dailyMaxExpressions;
        this.minInterval = minInterval;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
    }

    /**
     * 评估想法是否适合现在表达。
     */
    public Decision evaluate(Thought thought, GatekeeperContext context) {
        // 1. 静默时段
        if (isQuietHours() && !isCritical(thought)) {
            return new Decision.Wait(thought, "静默时段", Duration.ofHours(1));
        }

        // 2. 每日额度
        if (context.todayExpressedCount() >= dailyMaxExpressions && !isCritical(thought)) {
            return new Decision.Wait(thought, "今日额度已满", Duration.ofHours(6));
        }

        // 3. 用户正在对话中
        if (context.userInConversation()) {
            return new Decision.Wait(thought, "用户正在对话中", Duration.ofMinutes(5));
        }

        // 4. 间隔太短
        if (context.timeSinceLastExpress() != null
                && context.timeSinceLastExpress().compareTo(minInterval) < 0) {
            Duration remaining = minInterval.minus(context.timeSinceLastExpress());
            return new Decision.Wait(thought, "距上次表达间隔太短", remaining);
        }

        // 5. 通过所有门控 → Express
        return new Decision.Express(thought, resolveUrgency(thought));
    }

    private boolean isQuietHours() {
        LocalTime now = LocalTime.now();
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return now.isAfter(quietHoursStart) && now.isBefore(quietHoursEnd);
        }
        // 跨午夜：如 23:00 - 08:00
        return now.isAfter(quietHoursStart) || now.isBefore(quietHoursEnd);
    }

    private boolean isCritical(Thought thought) {
        // 有明确截止时间且 < 2 小时的提醒视为 CRITICAL
        return thought.kind() == ThoughtKind.REMINDER && thought.maturity() >= 0.9f;
    }

    private ExpressUrgency resolveUrgency(Thought thought) {
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
