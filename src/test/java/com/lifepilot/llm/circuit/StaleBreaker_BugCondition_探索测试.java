package com.lifepilot.llm.circuit;

import com.lifepilot.llm.config.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 陈旧熔断器状态 Bug Condition 探索测试。
 *
 * <p>验证 Bug 3：{@link CircuitBreakerManager} 构造时从数据库恢复所有持久化的熔断器状态，
 * 包括已不在当前配置中的 Provider（如 {@code ollama-nomic-embed:EMBEDDING}）的陈旧状态。
 * 当前代码没有 {@code purgeStaleBreakers()} 方法来清理这些陈旧实例。
 *
 * <p>此测试在未修复代码上预期失败，失败即确认 bug 存在。
 *
 * <p><b>Validates: Requirements 1.3, 2.3</b>
 *
 * @author zsg
 * @since 2026-03-08
 */
class StaleBreaker_BugCondition_探索测试 {

    private static final String STALE_KEY = "ollama-nomic-embed:EMBEDDING";
    private static final String VALID_KEY = "deepseek-chat:CHAT";

    private JdbcTemplate jdbcTemplate;
    private CircuitBreakerConfig config;

    @BeforeEach
    void setUp() {
        // 使用内存 SQLite 数据库（SingleConnectionDataSource 保持连接不关闭）
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

        var now = Instant.now().toString();

        // 插入陈旧条目：已不在当前配置中的 Provider
        jdbcTemplate.update("""
                INSERT INTO circuit_breaker_states
                    (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                VALUES (?, 'OPEN', 3, ?, ?, ?)
                """, STALE_KEY, now, now, now);

        // 插入有效条目：仍在当前配置中的 Provider
        jdbcTemplate.update("""
                INSERT INTO circuit_breaker_states
                    (provider_capability, state, failure_count, last_failure_at, state_changed_at, updated_at)
                VALUES (?, 'CLOSED', 0, NULL, ?, ?)
                """, VALID_KEY, now, now);

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
    @DisplayName("陈旧 Provider 的熔断器状态不应被恢复到内存中")
    void 陈旧Provider的熔断器状态_构造后不应存在于breakers中() throws Exception {
        // 定义当前活跃的 Provider ID 集合（不包含 ollama-nomic-embed）
        Set<String> activeProviderIds = Set.of("deepseek-chat");

        // 构造 CircuitBreakerManager，触发 restoreFromDatabase()
        var manager = new CircuitBreakerManager(config, jdbcTemplate);

        // 通过反射获取 breakers map
        var breakers = getBreakers(manager);

        // 有效 key 应该存在
        assertTrue(breakers.containsKey(VALID_KEY),
                "有效 Provider 的熔断器状态应被恢复: " + VALID_KEY);

        // Bug Condition 断言：陈旧 key 不应存在
        // 在未修复代码上，restoreFromDatabase() 无条件恢复所有行，
        // 所以 STALE_KEY 会存在于 breakers 中 → 断言失败 → 确认 bug 存在
        assertFalse(breakers.containsKey(STALE_KEY),
                "陈旧 Provider 的熔断器状态不应被恢复到内存中: " + STALE_KEY
                        + "（当前活跃 Provider: " + activeProviderIds + "）");
    }

    @Test
    @DisplayName("陈旧熔断器状态应从数据库中被清理")
    void 陈旧Provider的熔断器状态_应从数据库中删除() throws Exception {
        // 构造 CircuitBreakerManager，触发 restoreFromDatabase()
        var manager = new CircuitBreakerManager(config, jdbcTemplate);
        // 确认 manager 已恢复状态
        assertNotNull(getBreakers(manager));

        // 验证数据库中陈旧条目应被清理
        // 在未修复代码上，没有 purgeStaleBreakers() 方法，数据库中陈旧条目仍然存在
        var staleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM circuit_breaker_states WHERE provider_capability = ?",
                Integer.class, STALE_KEY);

        assertEquals(0, staleCount,
                "陈旧 Provider 的熔断器状态应从数据库中删除: " + STALE_KEY);
    }
}
