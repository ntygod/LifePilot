package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.FrequencyState;
import com.lifepilot.agent.proactive.model.FrequencyStateEntry;
import com.lifepilot.agent.proactive.model.NotificationType;
import com.lifepilot.agent.proactive.model.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 频率状态管理器 — 管理所有提醒类型的频率状态实例。
 *
 * <p>运行时使用 {@link ConcurrentHashMap} 缓存，同时持久化到 SQLite frequency_states 表。
 * 持久化失败时降级为纯内存模式，不阻塞业务逻辑。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FrequencyStateManager {

    private static final Logger log = LoggerFactory.getLogger(FrequencyStateManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final ProactiveConfigProperties config;
    private final ConcurrentHashMap<NotificationType, FrequencyStateEntry> cache = new ConcurrentHashMap<>();

    public FrequencyStateManager(JdbcTemplate jdbcTemplate, ProactiveConfigProperties config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * 启动时从 SQLite 加载持久化状态到内存缓存。
     */
    public void loadPersistedStates() {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT notification_type, state, consecutive_ignore_count, last_notified_at FROM frequency_states"
            );
            for (var row : rows) {
                var type = NotificationType.valueOf((String) row.get("notification_type"));
                var state = FrequencyState.valueOf((String) row.get("state"));
                var ignoreCount = ((Number) row.get("consecutive_ignore_count")).intValue();
                var lastNotifiedStr = (String) row.get("last_notified_at");
                var lastNotified = lastNotifiedStr != null ? Instant.parse(lastNotifiedStr) : Instant.EPOCH;
                cache.put(type, new FrequencyStateEntry(state, ignoreCount, lastNotified));
            }
            log.info("频率状态加载完成: count={}", cache.size());
        } catch (Exception e) {
            log.warn("频率状态加载失败，使用默认状态: error={}", e.getMessage());
        }
    }

    /**
     * 获取指定类型的频率状态。
     *
     * @param type 通知类型
     * @return 频率状态，不存在时返回 NORMAL
     */
    public FrequencyState getState(NotificationType type) {
        return cache.getOrDefault(type, FrequencyStateEntry.initial()).state();
    }

    /**
     * 判断指定类型在当前状态下是否允许发送指定紧急度的通知。
     *
     * @param type    通知类型
     * @param urgency 紧急程度
     * @return 是否允许发送
     */
    public boolean shouldSend(NotificationType type, Urgency urgency) {
        return getState(type).shouldSend(urgency);
    }

    /**
     * 判断指定类型是否在冷却期内。
     *
     * @param type 通知类型
     * @return 是否在冷却期内
     */
    public boolean isInCooldown(NotificationType type) {
        var entry = cache.getOrDefault(type, FrequencyStateEntry.initial());
        if (entry.lastNotifiedAt().equals(Instant.EPOCH)) {
            return false;
        }
        int multiplier = entry.state().intervalMultiplier(config.getReducedMultiplier());
        long cooldownMs = (long) config.getCooldownMinutes() * 60_000L * multiplier;
        return Duration.between(entry.lastNotifiedAt(), Instant.now()).toMillis() < cooldownMs;
    }

    /**
     * 记录用户忽略 — 递增连续忽略计数，触发状态转换。
     *
     * @param type 通知类型
     */
    public void recordIgnored(NotificationType type) {
        cache.compute(type, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            int newCount = entry.consecutiveIgnoreCount() + 1;
            var newState = entry.state().onIgnored(newCount, config.getIgnoreThreshold());
            return new FrequencyStateEntry(newState, newCount, entry.lastNotifiedAt());
        });
        persistState(type);
        log.debug("记录忽略: type={}, newState={}", type, getState(type));
    }

    /**
     * 记录用户确认 — 即时恢复到 NORMAL，清零忽略计数。
     *
     * @param type 通知类型
     */
    public void recordAcknowledged(NotificationType type) {
        cache.compute(type, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            return new FrequencyStateEntry(FrequencyState.NORMAL, 0, entry.lastNotifiedAt());
        });
        persistState(type);
        log.debug("记录确认: type={}, 恢复到 NORMAL", type);
    }

    /**
     * 更新最后通知时间。
     *
     * @param type 通知类型
     */
    public void updateLastNotified(NotificationType type) {
        cache.compute(type, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            return new FrequencyStateEntry(entry.state(), entry.consecutiveIgnoreCount(), Instant.now());
        });
        persistState(type);
    }

    /**
     * 持久化指定类型的频率状态到 SQLite。失败时仅记录 WARN 日志。
     */
    private void persistState(NotificationType type) {
        var entry = cache.getOrDefault(type, FrequencyStateEntry.initial());
        try {
            jdbcTemplate.update("""
                INSERT INTO frequency_states (notification_type, state, consecutive_ignore_count, last_notified_at, updated_at)
                VALUES (?, ?, ?, ?, datetime('now'))
                ON CONFLICT(notification_type) DO UPDATE SET
                    state = excluded.state,
                    consecutive_ignore_count = excluded.consecutive_ignore_count,
                    last_notified_at = excluded.last_notified_at,
                    updated_at = datetime('now')
                """,
                    type.name(),
                    entry.state().name(),
                    entry.consecutiveIgnoreCount(),
                    entry.lastNotifiedAt().equals(Instant.EPOCH) ? null : entry.lastNotifiedAt().toString()
            );
        } catch (Exception e) {
            log.warn("频率状态持久化失败: type={}, error={}", type, e.getMessage());
        }
    }
}
