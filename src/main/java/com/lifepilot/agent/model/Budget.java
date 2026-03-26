package com.lifepilot.agent.model;

import com.lifepilot.agent.config.AgentConfigProperties;
import lombok.Builder;

import java.time.Duration;

/**
 * 三维预算 record（Token / 步骤 / 时间）。
 *
 * <p>任一维度超限即触发终止，支持 SubAgent 预算分配。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@Builder(toBuilder = true)
public record Budget(
        int maxTokens,
        int tokensUsed,
        int tokensReserved,
        int maxSteps,
        int stepsUsed,
        Duration maxDuration,
        Duration elapsed
) {

    /** 从配置构建默认预算。 */
    public static Budget fromConfig(AgentConfigProperties.BudgetConfig budgetConfig) {
        return Budget.builder()
                .maxTokens(budgetConfig.getDefaultMaxTokens())
                .tokensUsed(0).tokensReserved(0)
                .maxSteps(budgetConfig.getDefaultMaxSteps())
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(budgetConfig.getDefaultMaxDurationSeconds()))
                .elapsed(Duration.ZERO)
                .build();
    }

    /** 任一维度超限返回 true。 */
    public boolean exceeded() {
        return tokensUsed >= maxTokens
                || stepsUsed >= maxSteps
                || elapsed.compareTo(maxDuration) >= 0;
    }

    /** 返回具体超限原因。 */
    public String exceedReason() {
        if (tokensUsed >= maxTokens) {
            return "Token 预算耗尽: " + tokensUsed + "/" + maxTokens;
        }
        if (stepsUsed >= maxSteps) {
            return "步骤预算耗尽: " + stepsUsed + "/" + maxSteps;
        }
        if (elapsed.compareTo(maxDuration) >= 0) {
            return "时间预算耗尽: " + elapsed + "/" + maxDuration;
        }
        return "预算未超限";
    }

    /** 剩余可用 Token。 */
    public int tokensRemaining() {
        return Math.max(0, maxTokens - tokensUsed - tokensReserved);
    }

    /** 剩余可用步骤数。 */
    public int stepsRemaining() {
        return Math.max(0, maxSteps - stepsUsed);
    }

    /** 剩余可用时长。 */
    public Duration durationRemaining() {
        var remaining = maxDuration.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** 返回只包含剩余额度的新预算实例。 */
    public Budget remainingBudget() {
        return Budget.builder()
                .maxTokens(tokensRemaining())
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(stepsRemaining())
                .stepsUsed(0)
                .maxDuration(durationRemaining())
                .elapsed(Duration.ZERO)
                .build();
    }

    /** 扣减 Token，返回新实例。 */
    public Budget deductTokens(int tokens) {
        return this.toBuilder().tokensUsed(this.tokensUsed + tokens).build();
    }

    /** 递增步骤计数，返回新实例。 */
    public Budget incrementStep() {
        return this.toBuilder().stepsUsed(this.stepsUsed + 1).build();
    }

    /** 更新已用时间，返回新实例。 */
    public Budget withElapsed(Duration elapsed) {
        return this.toBuilder().elapsed(elapsed).build();
    }

    /** 为 SubAgent 分配预算。 */
    public Budget allocateForSubAgent(double ratio) {
        double normalizedRatio = Math.max(0.0, Math.min(1.0, ratio));
        Budget remaining = remainingBudget();
        int subTokens = scaleByRatio(remaining.maxTokens(), normalizedRatio);
        int subSteps = scaleByRatio(remaining.maxSteps(), normalizedRatio);
        Duration subDuration = scaleDuration(remaining.maxDuration(), normalizedRatio);
        return Budget.builder()
                .maxTokens(subTokens).tokensUsed(0).tokensReserved(0)
                .maxSteps(subSteps).stepsUsed(0)
                .maxDuration(subDuration)
                .elapsed(Duration.ZERO)
                .build();
    }

    /**
     * 归还 SubAgent 未使用的 Token。
     * <p>SubAgent 分配时从父预算预留了 Token（tokensReserved），
     * 归还时将未使用部分释放回可用池。</p>
     */
    public Budget returnFromSubAgent(Budget subBudget) {
        int allocated = subBudget.maxTokens;
        int used = subBudget.tokensUsed;
        int unused = allocated - used;
        // 从预留中释放已分配的额度，未使用部分回到可用池
        return this.toBuilder()
                .tokensReserved(Math.max(0, this.tokensReserved - allocated))
                .tokensUsed(this.tokensUsed + used)
                .build();
    }

    /** Token 使用率（0.0 ~ 1.0）。 */
    public double tokenUtilization() {
        return maxTokens == 0 ? 0.0 : (double) tokensUsed / maxTokens;
    }

    /** 时间使用率（0.0 ~ 1.0）。 */
    public double timeUtilization() {
        return maxDuration.isZero() ? 0.0
                : (double) elapsed.toMillis() / maxDuration.toMillis();
    }

    /**
     * 渐进式降级等级 — 根据 Token 使用率返回当前降级阶段。
     *
     * <ul>
     *   <li>{@code NORMAL} — 使用率 &lt; 80%，正常运行</li>
     *   <li>{@code COMPRESS_HISTORY} — 使用率 80%~90%，应压缩对话历史</li>
     *   <li>{@code TRIM_TOOLS} — 使用率 90%~95%，应裁剪工具 Schema 到最小集</li>
     *   <li>{@code SKIP_MEMORY} — 使用率 95%~100%，应停止注入记忆</li>
     *   <li>{@code TERMINATE} — 使用率 ≥ 100%，终止并生成摘要</li>
     * </ul>
     *
     * @return 当前降级等级
     */
    public DegradationLevel degradationLevel() {
        double utilization = tokenUtilization();
        if (utilization >= 1.0) return DegradationLevel.TERMINATE;
        if (utilization >= 0.95) return DegradationLevel.SKIP_MEMORY;
        if (utilization >= 0.90) return DegradationLevel.TRIM_TOOLS;
        if (utilization >= 0.80) return DegradationLevel.COMPRESS_HISTORY;
        return DegradationLevel.NORMAL;
    }

    /**
     * 预算渐进式降级等级。
     */
    public enum DegradationLevel {
        /** 正常运行，无需降级。 */
        NORMAL,
        /** 压缩对话历史。 */
        COMPRESS_HISTORY,
        /** 裁剪工具 Schema 到最小集。 */
        TRIM_TOOLS,
        /** 停止注入记忆。 */
        SKIP_MEMORY,
        /** 终止并生成摘要。 */
        TERMINATE
    }

    private static int scaleByRatio(int value, double ratio) {
        if (value <= 0 || ratio <= 0.0) {
            return 0;
        }
        return (int) Math.floor(value * ratio);
    }

    private static Duration scaleDuration(Duration value, double ratio) {
        if (value.isZero() || value.isNegative() || ratio <= 0.0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis((long) Math.floor(value.toMillis() * ratio));
    }
}
