package com.lifepilot.agent.model;

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
        Duration maxDuration,
        Duration elapsed
) {

    /** 默认预算：32000 Token、20 步、120 秒。 */
    public static Budget defaultBudget() {
        return Budget.builder()
                .maxTokens(32000).tokensUsed(0).tokensReserved(0)
                .maxSteps(20)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ZERO)
                .build();
    }

    /** 任一维度超限返回 true。 */
    public boolean exceeded() {
        return tokensUsed >= maxTokens
                || elapsed.compareTo(maxDuration) >= 0;
    }

    /** 返回具体超限原因。 */
    public String exceedReason() {
        if (tokensUsed >= maxTokens) {
            return "Token 预算耗尽: " + tokensUsed + "/" + maxTokens;
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

    /** 扣减 Token，返回新实例。 */
    public Budget deductTokens(int tokens) {
        return this.toBuilder().tokensUsed(this.tokensUsed + tokens).build();
    }

    /** 更新已用时间，返回新实例。 */
    public Budget withElapsed(Duration elapsed) {
        return this.toBuilder().elapsed(elapsed).build();
    }

    /** 为 SubAgent 分配预算。 */
    public Budget allocateForSubAgent(double ratio) {
        int subTokens = (int) (tokensRemaining() * ratio);
        int subSteps = (int) (maxSteps * ratio);
        Duration subDuration = maxDuration.multipliedBy((long) (ratio * 100)).dividedBy(100);
        return Budget.builder()
                .maxTokens(subTokens).tokensUsed(0).tokensReserved(0)
                .maxSteps(subSteps)
                .maxDuration(subDuration)
                .elapsed(Duration.ZERO)
                .build();
    }

    /** 归还 SubAgent 未使用的 Token。 */
    public Budget returnFromSubAgent(Budget subBudget) {
        int unused = subBudget.tokensRemaining();
        return this.toBuilder()
                .tokensReserved(Math.max(0, this.tokensReserved - unused))
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
}
