package com.lifepilot.llm.circuit;

import com.lifepilot.llm.config.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 有效熔断器状态保留 Preservation 保持测试。
 *
 * <p>验证 Property 6：{@link CircuitBreakerManager} 从数据库恢复有效 Provider 的
 * 熔断器状态（CLOSED/OPEN/HALF_OPEN）时，状态正确保留在 {@code breakers} map 中。
 *
 * <p>这些测试在未修复代码上必须通过，确保修复 Bug 3 后不会引入回归。
 *
 * <p><b>Validates: Requirements 3.4</b>
 *
 * @author zsg
 * @since 2026-03-08
 */
class StaleBreaker_Preservation_保持测试 {

    private JdbcTemplate jdbcTemplate;
    private CircuitBreakerConfig config;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建 circuit_breaker_states 表
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS circuit_breaker_states (
                    provider_capability TEXT PRIMARY KEY,
                    state TEXT NOT NULL DEFAULT 'CLOSED'
                        CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
                    failure_count INTEGER NOT NULL DEFAULT 0,
                    last_failure_at TEXT,
                    state_changed_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);

        config = CircuitBreakerConfig.defaults();
    }

    /**
     * 通过反射获取 CircuitBreakerManager 的 breakers 字段。
     */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, CircuitBreaker> getBreakers(CircuitBreakerManager manager) throws Exception {
        Field field = CircuitBreakerManager.class.getDeclaredField("breakers");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, CircuitBreaker>) field.get(manager);
    }

    @Test
    @DisplayName("数据库中有效 Provider 的 OPEN 状态 → 恢复后 breakers 包含该 key 且状态为 OPEN")
    void 有效Provider的OPEN状态_恢复后正确保留() throws Exception {
        var key = "deepseek-chat:CHAT";
        var now = Instant.now().toString();

        // 插入 OPEN 状态的有效 Provider
        jdbcTemplate.update("""
                INSERT INTO circuit_breaker_states
                    (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                VALUES (?, 'OPEN', 3, ?, ?, ?)
                """, key, now, now, now);

        // 构造 CircuitBreakerManager，触发 restoreFromDatabase()
        var manager = new CircuitBreakerManager(config, jdbcTemplate);
        var breakers = getBreakers(manager);

        // 验证 key 存在且状态为 OPEN
        assertTrue(breakers.containsKey(key),
                "有效 Provider 的 OPEN 状态应被恢复: " + key);

        var breaker = breakers.get(key);
        assertEquals("OPEN", breaker.getState().stateName(),
                "恢复后的状态应为 OPEN");
        assertInstanceOf(CircuitState.Open.class, breaker.getState(),
                "状态类型应为 CircuitState.Open");
    }

    @Test
    @DisplayName("数据库中有效 Provider 的 CLOSED 状态 → 恢复后 breakers 包含该 key 且状态为 CLOSED")
    void 有效Provider的CLOSED状态_恢复后正确保留() throws Exception {
        var key = "openai-gpt4:CHAT";
        var now = Instant.now().toString();

        // 插入 CLOSED 状态的有效 Provider
        jdbcTemplate.update("""
                INSERT INTO circuit_breaker_states
                    (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                VALUES (?, 'CLOSED', 0, NULL, ?, ?)
                """, key, now, now);

        // 构造 CircuitBreakerManager，触发 restoreFromDatabase()
        var manager = new CircuitBreakerManager(config, jdbcTemplate);
        var breakers = getBreakers(manager);

        // 验证 key 存在且状态为 CLOSED
        assertTrue(breakers.containsKey(key),
                "有效 Provider 的 CLOSED 状态应被恢复: " + key);

        var breaker = breakers.get(key);
        assertEquals("CLOSED", breaker.getState().stateName(),
                "恢复后的状态应为 CLOSED");
        assertInstanceOf(CircuitState.Closed.class, breaker.getState(),
                "状态类型应为 CircuitState.Closed");
    }

    @Test
    @DisplayName("数据库中有效 Provider 的 HALF_OPEN 状态 → 恢复后 breakers 包含该 key 且状态为 HALF_OPEN")
    void 有效Provider的HALF_OPEN状态_恢复后正确保留() throws Exception {
        var key = "claude-sonnet:CHAT";
        var now = Instant.now().toString();

        // 插入 HALF_OPEN 状态的有效 Provider
        jdbcTemplate.update("""
                INSERT INTO circuit_breaker_states
                    (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                VALUES (?, 'HALF_OPEN', 0, NULL, ?, ?)
                """, key, now, now);

        // 构造 CircuitBreakerManager，触发 restoreFromDatabase()
        var manager = new CircuitBreakerManager(config, jdbcTemplate);
        var breakers = getBreakers(manager);

        // 验证 key 存在且状态为 HALF_OPEN
        assertTrue(breakers.containsKey(key),
                "有效 Provider 的 HALF_OPEN 状态应被恢复: " + key);

        var breaker = breakers.get(key);
        assertEquals("HALF_OPEN", breaker.getState().stateName(),
                "恢复后的状态应为 HALF_OPEN");
        assertInstanceOf(CircuitState.HalfOpen.class, breaker.getState(),
                "状态类型应为 CircuitState.HalfOpen");
    }
}
