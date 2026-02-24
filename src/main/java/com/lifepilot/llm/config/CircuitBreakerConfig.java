package com.lifepilot.llm.config;

/**
 * 熔断器配置参数。
 *
 * @param failureThreshold     触发熔断的连续失败阈值（默认 3）
 * @param resetTimeoutSeconds  OPEN 状态超时秒数（默认 60）
 * @param halfOpenMaxAttempts  HALF_OPEN 最大探测次数（默认 1）
 * @param retryInitialDelayMs  重试初始延迟毫秒（默认 500）
 * @param retryMultiplier      重试延迟倍数（默认 2.0）
 * @param retryMaxDelayMs      重试最大延迟毫秒（默认 5000）
 * @author zsg
 * @since 2026-02-24
 */
public record CircuitBreakerConfig(
        int failureThreshold,
        int resetTimeoutSeconds,
        int halfOpenMaxAttempts,
        int retryInitialDelayMs,
        double retryMultiplier,
        int retryMaxDelayMs
) {

    /**
     * 创建默认配置。
     *
     * @return 默认熔断器配置
     */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(3, 60, 1, 500, 2.0, 5000);
    }
}
