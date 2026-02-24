package com.lifepilot.llm.circuit;

import java.time.Instant;

/**
 * 熔断器状态 sealed interface。
 *
 * <p>三态状态机：CLOSED（正常）→ OPEN（熔断）→ HALF_OPEN（探测）。
 * 使用 switch 表达式穷举匹配。
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface CircuitState
        permits CircuitState.Closed, CircuitState.Open, CircuitState.HalfOpen {

    /**
     * 正常关闭状态，允许所有调用。
     *
     * @param consecutiveFailures 连续失败次数
     */
    record Closed(int consecutiveFailures) implements CircuitState {
        /** 创建初始状态（失败计数为 0）。 */
        public static Closed initial() {
            return new Closed(0);
        }
    }

    /**
     * 熔断打开状态，拒绝所有调用。
     *
     * @param openedAt     熔断触发时间
     * @param failureCount 触发熔断时的失败次数
     */
    record Open(Instant openedAt, int failureCount) implements CircuitState {
    }

    /**
     * 半开状态，允许有限探测调用。
     *
     * @param transitionedAt 进入半开状态的时间
     */
    record HalfOpen(Instant transitionedAt) implements CircuitState {
    }

    /**
     * 获取状态名称字符串。
     *
     * @return CLOSED / OPEN / HALF_OPEN
     */
    default String stateName() {
        return switch (this) {
            case Closed _ -> "CLOSED";
            case Open _ -> "OPEN";
            case HalfOpen _ -> "HALF_OPEN";
        };
    }
}
