package com.lifepilot.interaction.middleware.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流数据结构，以 LLM Token 为单位，线性补充。
 *
 * <p>使用 {@link AtomicLong} + CAS 操作保证线程安全，无 synchronized。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #tryConsume(long)} 返回 false 时，availableTokens 不变（无部分消费）</li>
 *   <li>{@link #refund(long)} 后 availableTokens 不超过 capacity</li>
 *   <li>{@link #availableTokens()} 始终在 [0, capacity] 范围内</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TokenBucket {

    /** 桶容量（maxTokensPerHour） */
    private final long capacity;

    /** 每毫秒补充速率 = capacity / 3_600_000.0 */
    private final double refillRatePerMs;

    /** 当前可用令牌数 */
    private final AtomicLong availableTokens;

    /** 上次补充时间戳（毫秒） */
    private final AtomicLong lastRefillTimestamp;

    /**
     * 创建令牌桶。
     *
     * @param capacity 桶容量，必须大于 0
     * @throws IllegalArgumentException 如果 capacity ≤ 0
     */
    public TokenBucket(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须大于 0，当前值: " + capacity);
        }
        this.capacity = capacity;
        this.refillRatePerMs = capacity / 3_600_000.0;
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillTimestamp = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * 尝试消费指定数量的令牌。
     *
     * <p>如果可用令牌不足，返回 false 且不进行部分消费。
     *
     * @param tokens 要消费的令牌数，必须大于 0
     * @return true 如果消费成功，false 如果令牌不足
     * @throws IllegalArgumentException 如果 tokens ≤ 0
     */
    public boolean tryConsume(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens 必须大于 0，当前值: " + tokens);
        }
        refill();
        while (true) {
            long current = availableTokens.get();
            if (current < tokens) {
                // 令牌不足，不部分消费
                return false;
            }
            if (availableTokens.compareAndSet(current, current - tokens)) {
                return true;
            }
            // CAS 失败，重试
        }
    }

    /**
     * 退还令牌，退还后可用令牌不超过 capacity。
     *
     * @param tokens 要退还的令牌数，必须大于 0
     * @throws IllegalArgumentException 如果 tokens ≤ 0
     */
    public void refund(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens 必须大于 0，当前值: " + tokens);
        }
        while (true) {
            long current = availableTokens.get();
            long newValue = Math.min(current + tokens, capacity);
            if (availableTokens.compareAndSet(current, newValue)) {
                return;
            }
            // CAS 失败，重试
        }
    }

    /**
     * 获取当前可用令牌数（触发补充后返回）。
     *
     * @return 当前可用令牌数，范围 [0, capacity]
     */
    public long availableTokens() {
        refill();
        return availableTokens.get();
    }

    /**
     * 获取桶容量。
     *
     * @return 桶容量
     */
    public long capacity() {
        return capacity;
    }

    /**
     * 基于 elapsed time 线性补充令牌。
     *
     * <p>使用 CAS 操作更新 lastRefillTimestamp，确保并发安全。
     */
    private void refill() {
        while (true) {
            long lastRefill = lastRefillTimestamp.get();
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed <= 0) {
                // 时间未前进，无需补充
                return;
            }
            long tokensToAdd = (long) (elapsed * refillRatePerMs);
            if (tokensToAdd <= 0) {
                // 补充量不足 1 个令牌，跳过
                return;
            }
            // CAS 更新时间戳，只有一个线程能成功
            if (lastRefillTimestamp.compareAndSet(lastRefill, now)) {
                // 成功获取补充权，增加令牌
                while (true) {
                    long current = availableTokens.get();
                    long newValue = Math.min(current + tokensToAdd, capacity);
                    if (availableTokens.compareAndSet(current, newValue)) {
                        return;
                    }
                    // CAS 失败，重试增加令牌
                }
            }
            // CAS 失败，其他线程已补充，重新检查
        }
    }
}
