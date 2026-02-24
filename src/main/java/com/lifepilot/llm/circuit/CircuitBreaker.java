package com.lifepilot.llm.circuit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器实现。
 *
 * <p>管理单个 providerId:capabilityType 组合的熔断状态，
 * 使用 {@link AtomicReference} + CAS 保证并发安全。
 *
 * <p>状态转换规则：
 * <ul>
 *   <li>CLOSED → OPEN：连续失败达到阈值</li>
 *   <li>OPEN → HALF_OPEN：超过 resetTimeout</li>
 *   <li>HALF_OPEN → CLOSED：探测成功</li>
 *   <li>HALF_OPEN → OPEN：探测失败</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String key;
    private final int failureThreshold;
    private final Duration resetTimeout;
    private final int halfOpenMaxAttempts;
    private final AtomicReference<CircuitState> state;
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    /**
     * 创建熔断器（初始状态为 CLOSED）。
     *
     * @param key                  熔断器标识（providerId:capabilityType）
     * @param failureThreshold     触发熔断的连续失败阈值
     * @param resetTimeout         OPEN 状态超时后自动转 HALF_OPEN
     * @param halfOpenMaxAttempts  HALF_OPEN 状态允许的最大探测次数
     */
    public CircuitBreaker(String key, int failureThreshold,
                          Duration resetTimeout, int halfOpenMaxAttempts) {
        this(key, failureThreshold, resetTimeout, halfOpenMaxAttempts,
                CircuitState.Closed.initial());
    }

    /**
     * 创建熔断器（从持久化恢复指定初始状态）。
     *
     * @param key                  熔断器标识
     * @param failureThreshold     触发熔断的连续失败阈值
     * @param resetTimeout         OPEN 状态超时时间
     * @param halfOpenMaxAttempts  HALF_OPEN 最大探测次数
     * @param initialState         初始状态
     */
    public CircuitBreaker(String key, int failureThreshold,
                          Duration resetTimeout, int halfOpenMaxAttempts,
                          CircuitState initialState) {
        this.key = key;
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
        this.halfOpenMaxAttempts = halfOpenMaxAttempts;
        this.state = new AtomicReference<>(initialState);
    }

    /**
     * 判断当前是否允许调用。
     *
     * <p>CLOSED 始终允许；OPEN 超时后自动转 HALF_OPEN；HALF_OPEN 限制探测次数。
     *
     * @return 允许调用返回 true
     */
    public boolean isCallPermitted() {
        var current = state.get();
        return switch (current) {
            case CircuitState.Closed _ -> true;
            case CircuitState.Open open -> {
                // 检查是否超过 resetTimeout，自动转 HALF_OPEN
                if (Duration.between(open.openedAt(), Instant.now()).compareTo(resetTimeout) > 0) {
                    var halfOpen = new CircuitState.HalfOpen(Instant.now());
                    if (state.compareAndSet(current, halfOpen)) {
                        halfOpenAttempts.set(0);
                        log.info("熔断器状态转换: key={}, OPEN -> HALF_OPEN", key);
                        yield true;
                    }
                    // CAS 失败，重新检查
                    yield isCallPermitted();
                }
                yield false;
            }
            case CircuitState.HalfOpen _ ->
                    halfOpenAttempts.incrementAndGet() <= halfOpenMaxAttempts;
        };
    }

    /**
     * 记录调用成功。
     *
     * <p>CLOSED 状态重置失败计数；HALF_OPEN 状态恢复为 CLOSED。
     */
    public void recordSuccess() {
        state.getAndUpdate(current -> switch (current) {
            case CircuitState.Closed _ -> CircuitState.Closed.initial();
            case CircuitState.HalfOpen _ -> {
                log.info("熔断器状态转换: key={}, HALF_OPEN -> CLOSED", key);
                yield CircuitState.Closed.initial();
            }
            case CircuitState.Open _ -> current; // OPEN 状态下不应有成功调用
        });
    }

    /**
     * 记录调用失败。
     *
     * <p>CLOSED 状态累加失败计数，达到阈值触发 OPEN；HALF_OPEN 状态重新进入 OPEN。
     */
    public void recordFailure() {
        state.getAndUpdate(current -> switch (current) {
            case CircuitState.Closed closed -> {
                int newCount = closed.consecutiveFailures() + 1;
                if (newCount >= failureThreshold) {
                    log.warn("熔断器触发: key={}, failures={}", key, newCount);
                    yield new CircuitState.Open(Instant.now(), newCount);
                }
                yield new CircuitState.Closed(newCount);
            }
            case CircuitState.HalfOpen _ -> {
                log.info("熔断器状态转换: key={}, HALF_OPEN -> OPEN", key);
                yield new CircuitState.Open(Instant.now(), failureThreshold);
            }
            case CircuitState.Open _ -> current; // 已经是 OPEN，保持不变
        });
    }

    /**
     * 获取当前状态。
     *
     * @return 熔断器状态
     */
    public CircuitState getState() {
        return state.get();
    }

    /**
     * 强制重置为 CLOSED 初始状态。
     */
    public void reset() {
        state.set(CircuitState.Closed.initial());
        halfOpenAttempts.set(0);
        log.info("熔断器强制重置: key={}", key);
    }

    /**
     * 获取熔断器标识。
     *
     * @return 标识键
     */
    public String key() {
        return key;
    }
}
