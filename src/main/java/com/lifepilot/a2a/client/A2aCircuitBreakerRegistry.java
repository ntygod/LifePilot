package com.lifepilot.a2a.client;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.llm.circuit.CircuitBreaker;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A 远程 Agent 熔断器注册表。
 *
 * <p>以远程 Agent URL 为 key，内存管理 per-URL 熔断器实例。
 * 复用 {@link CircuitBreaker} 实现，不持久化到数据库（远程 Agent 重启后自动恢复）。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class A2aCircuitBreakerRegistry {

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Duration resetTimeout;
    private final int halfOpenMaxAttempts;

    public A2aCircuitBreakerRegistry(A2aProperties properties) {
        var client = properties.getClient();
        this.failureThreshold = client.getCircuitBreakerFailureThreshold();
        this.resetTimeout = Duration.ofSeconds(client.getCircuitBreakerResetTimeoutSeconds());
        this.halfOpenMaxAttempts = client.getCircuitBreakerHalfOpenMaxAttempts();
    }

    /** 判断指定 Agent URL 是否允许调用。 */
    public boolean isCallPermitted(String agentUrl) {
        return getOrCreate(agentUrl).isCallPermitted();
    }

    /** 记录调用成功。 */
    public void recordSuccess(String agentUrl) {
        getOrCreate(agentUrl).recordSuccess();
    }

    /** 记录调用失败。 */
    public void recordFailure(String agentUrl) {
        getOrCreate(agentUrl).recordFailure();
    }

    private CircuitBreaker getOrCreate(String agentUrl) {
        return breakers.computeIfAbsent(agentUrl, url ->
                new CircuitBreaker(url, failureThreshold, resetTimeout, halfOpenMaxAttempts));
    }
}
