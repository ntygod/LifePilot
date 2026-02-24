package com.lifepilot.llm.circuit;

import com.lifepilot.llm.config.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 熔断器管理器。
 *
 * <p>以 {@code providerId:capabilityType} 复合键隔离管理多个熔断器实例，
 * 状态变更时异步持久化到 circuit_breaker_states 表。
 *
 * @author zsg
 * @since 2026-02-24
 */
@Service
public class CircuitBreakerManager {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerManager.class);

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig config;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建熔断器管理器，初始化时从数据库恢复状态。
     *
     * @param config       熔断器配置
     * @param jdbcTemplate JDBC 模板
     */
    public CircuitBreakerManager(CircuitBreakerConfig config, JdbcTemplate jdbcTemplate) {
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
        restoreFromDatabase();
    }

    /**
     * 判断指定 Provider 能力是否允许调用。
     *
     * @param providerId     Provider ID
     * @param capabilityType 能力类型
     * @return 允许调用返回 true
     */
    public boolean isCallPermitted(String providerId, String capabilityType) {
        return getOrCreate(providerId, capabilityType).isCallPermitted();
    }

    /**
     * 记录调用成功。
     *
     * @param providerId     Provider ID
     * @param capabilityType 能力类型
     */
    public void recordSuccess(String providerId, String capabilityType) {
        var breaker = getOrCreate(providerId, capabilityType);
        var before = breaker.getState();
        breaker.recordSuccess();
        var after = breaker.getState();
        if (!before.stateName().equals(after.stateName())) {
            persistAsync(breaker.key(), after);
        }
    }

    /**
     * 记录调用失败。
     *
     * @param providerId     Provider ID
     * @param capabilityType 能力类型
     */
    public void recordFailure(String providerId, String capabilityType) {
        var breaker = getOrCreate(providerId, capabilityType);
        var before = breaker.getState();
        breaker.recordFailure();
        var after = breaker.getState();
        if (!before.stateName().equals(after.stateName())) {
            persistAsync(breaker.key(), after);
        }
    }

    /**
     * 获取指定熔断器状态。
     *
     * @param providerId     Provider ID
     * @param capabilityType 能力类型
     * @return 熔断器状态
     */
    public CircuitState getState(String providerId, String capabilityType) {
        return getOrCreate(providerId, capabilityType).getState();
    }

    /**
     * 获取所有熔断器状态的不可变快照。
     *
     * @return 键为 providerId:capabilityType，值为状态
     */
    public Map<String, CircuitState> getAllStates() {
        return breakers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getState()
                ));
    }

    /**
     * 手动重置指定熔断器。
     *
     * @param providerId     Provider ID
     * @param capabilityType 能力类型
     */
    public void reset(String providerId, String capabilityType) {
        var breaker = getOrCreate(providerId, capabilityType);
        breaker.reset();
        persistAsync(breaker.key(), breaker.getState());
    }

    // --- 内部方法 ---

    private String buildKey(String providerId, String capabilityType) {
        return providerId + ":" + capabilityType;
    }

    private CircuitBreaker getOrCreate(String providerId, String capabilityType) {
        var key = buildKey(providerId, capabilityType);
        return breakers.computeIfAbsent(key, k -> new CircuitBreaker(
                k,
                config.failureThreshold(),
                Duration.ofSeconds(config.resetTimeoutSeconds()),
                config.halfOpenMaxAttempts()
        ));
    }

    /**
     * 从数据库恢复熔断器状态。异常时记录 WARN 日志继续。
     */
    private void restoreFromDatabase() {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT provider_capability, state, failure_count, state_changed_at FROM circuit_breaker_states"
            );
            for (var row : rows) {
                var key = (String) row.get("provider_capability");
                var stateName = (String) row.get("state");
                var failureCount = ((Number) row.get("failure_count")).intValue();
                var stateChangedAt = Instant.parse((String) row.get("state_changed_at"));

                CircuitState initialState = switch (stateName) {
                    case "CLOSED" -> new CircuitState.Closed(failureCount);
                    case "OPEN" -> new CircuitState.Open(stateChangedAt, failureCount);
                    case "HALF_OPEN" -> new CircuitState.HalfOpen(stateChangedAt);
                    default -> CircuitState.Closed.initial();
                };

                breakers.put(key, new CircuitBreaker(
                        key,
                        config.failureThreshold(),
                        Duration.ofSeconds(config.resetTimeoutSeconds()),
                        config.halfOpenMaxAttempts(),
                        initialState
                ));
                log.debug("恢复熔断器状态: key={}, state={}", key, stateName);
            }
            if (!rows.isEmpty()) {
                log.info("熔断器状态恢复完成: count={}", rows.size());
            }
        } catch (Exception e) {
            log.warn("熔断器状态恢复失败，使用默认初始状态: error={}", e.getMessage());
        }
    }

    /**
     * 异步持久化熔断器状态。
     */
    private void persistAsync(String key, CircuitState state) {
        CompletableFuture.runAsync(() -> {
            try {
                var now = Instant.now().toString();
                int failureCount = switch (state) {
                    case CircuitState.Closed closed -> closed.consecutiveFailures();
                    case CircuitState.Open open -> open.failureCount();
                    case CircuitState.HalfOpen _ -> 0;
                };
                var lastFailureAt = state instanceof CircuitState.Open
                        ? now : null;

                jdbcTemplate.update("""
                        INSERT INTO circuit_breaker_states
                            (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(provider_capability) DO UPDATE SET
                            state = excluded.state,
                            failure_count = excluded.failure_count,
                            last_failure_at = excluded.last_failure_at,
                            state_changed_at = excluded.state_changed_at,
                            updated_at = excluded.updated_at
                        """,
                        key, state.stateName(), failureCount, lastFailureAt, now, now
                );
                log.debug("熔断器状态持久化: key={}, state={}", key, state.stateName());
            } catch (Exception e) {
                log.warn("熔断器状态持久化失败: key={}, error={}", key, e.getMessage());
            }
        });
    }
}
