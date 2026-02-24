package com.lifepilot.llm;

/**
 * 指数退避计算器。
 *
 * <p>用于故障转移间的等待时间计算，避免短时间内对故障 Provider 的重复冲击。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ExponentialBackoff(
        long initialDelayMs,
        double multiplier,
        long maxDelayMs,
        int maxRetries
) {

    /**
     * 创建符合编码规范默认配置的退避实例。
     *
     * <p>初始延迟 500ms，倍数 2.0，上限 5000ms，最多重试 2 次。
     *
     * @return 默认配置
     */
    public static ExponentialBackoff defaults() {
        return new ExponentialBackoff(500, 2.0, 5000, 2);
    }

    /**
     * 计算指定重试次数的延迟时间。
     *
     * @param attempt 重试次数（从 0 开始）
     * @return 延迟毫秒数，不超过 maxDelayMs
     */
    public long delayForAttempt(int attempt) {
        if (attempt <= 0) return initialDelayMs;
        return Math.min((long) (initialDelayMs * Math.pow(multiplier, attempt)), maxDelayMs);
    }
}
