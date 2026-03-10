package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.FrequencyState;
import com.lifepilot.agent.proactive.model.FrequencyStateEntry;
import com.lifepilot.agent.proactive.model.NotificationTypeDefinition;
import com.lifepilot.agent.proactive.model.Urgency;
import org.springframework.lang.Nullable;
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
 * <p>缓存键由 {@code typeId} 和可选的 {@code subjectId} 组合生成，
 * 支持按具体对象级别的频率控制。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FrequencyStateManager {

    private static final Logger log = LoggerFactory.getLogger(FrequencyStateManager.class);

    /** 默认冷却时间（分钟），当 Registry 和 Config 均无配置时使用。 */
    private static final int DEFAULT_COOLDOWN_MINUTES = 120;

    private final JdbcTemplate jdbcTemplate;
    private final ProactiveConfigProperties config;
    private final NotificationTypeRegistry typeRegistry;
    private final ConcurrentHashMap<String, FrequencyStateEntry> cache = new ConcurrentHashMap<>();

    public FrequencyStateManager(JdbcTemplate jdbcTemplate,
                                 ProactiveConfigProperties config,
                                 NotificationTypeRegistry typeRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.typeRegistry = typeRegistry;
    }

    /**
     * 构建缓存键。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     * @return 缓存键字符串
     */
    private String buildCacheKey(String typeId, @Nullable String subjectId) {
        return subjectId != null && !subjectId.isEmpty() ? typeId + ":" + subjectId : typeId;
    }

    /**
     * 启动时从 SQLite 加载持久化状态到内存缓存。
     */
    public void loadPersistedStates() {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT notification_type, subject_id, state, consecutive_ignore_count, last_notified_at FROM frequency_states"
            );
            for (var row : rows) {
                var typeId = (String) row.get("notification_type");
                var subjectId = (String) row.get("subject_id");
                var state = FrequencyState.valueOf((String) row.get("state"));
                var ignoreCount = ((Number) row.get("consecutive_ignore_count")).intValue();
                var lastNotifiedStr = (String) row.get("last_notified_at");
                var lastNotified = lastNotifiedStr != null ? Instant.parse(lastNotifiedStr) : Instant.EPOCH;
                var cacheKey = buildCacheKey(typeId, subjectId);
                cache.put(cacheKey, new FrequencyStateEntry(state, ignoreCount, lastNotified));
            }
            log.info("频率状态加载完成: count={}", cache.size());
        } catch (Exception e) {
            log.warn("频率状态加载失败，使用默认状态: error={}", e.getMessage());
        }
    }

    /**
     * 获取指定类型和主体的频率状态。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     * @return 频率状态，不存在时返回 NORMAL
     */
    public FrequencyState getState(String typeId, @Nullable String subjectId) {
        return cache.getOrDefault(buildCacheKey(typeId, subjectId), FrequencyStateEntry.initial()).state();
    }

    /**
     * 判断指定类型和主体在当前状态下是否允许发送指定紧急度的通知。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     * @param urgency   紧急程度
     * @return 是否允许发送
     */
    public boolean shouldSend(String typeId, @Nullable String subjectId, Urgency urgency) {
        return getState(typeId, subjectId).shouldSend(urgency);
    }

    /**
     * 获取指定类型和主体的最后通知时间。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     * @return 最后通知时间，未通知过时返回 Instant.EPOCH
     */
    public Instant getLastNotifiedAt(String typeId, @Nullable String subjectId) {
        return cache.getOrDefault(buildCacheKey(typeId, subjectId), FrequencyStateEntry.initial()).lastNotifiedAt();
    }

    /**
     * 判断指定类型和主体是否在冷却期内。
     *
     * <p>冷却时间查询优先级：NotificationTypeRegistry 默认值 → 硬编码 120 分钟。</p>
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     * @return 是否在冷却期内
     */
    public boolean isInCooldown(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        var entry = cache.getOrDefault(cacheKey, FrequencyStateEntry.initial());
        if (entry.lastNotifiedAt().equals(Instant.EPOCH)) {
            return false;
        }
        int multiplier = entry.state().intervalMultiplier(config.getReducedMultiplier());
        int cooldownMinutes = resolveCooldownMinutes(typeId);
        long cooldownMs = (long) cooldownMinutes * 60_000L * multiplier;
        return Duration.between(entry.lastNotifiedAt(), Instant.now()).toMillis() < cooldownMs;
    }

    /**
     * 记录用户忽略 — 递增连续忽略计数，触发状态转换。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     */
    public void recordIgnored(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        cache.compute(cacheKey, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            int newCount = entry.consecutiveIgnoreCount() + 1;
            var newState = entry.state().onIgnored(newCount, config.getIgnoreThreshold());
            return new FrequencyStateEntry(newState, newCount, entry.lastNotifiedAt());
        });
        persistState(typeId, subjectId);
        log.debug("记录忽略: typeId={}, subjectId={}, newState={}", typeId, subjectId, getState(typeId, subjectId));
    }

    /**
     * 记录用户确认 — 即时恢复到 NORMAL，清零忽略计数。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     */
    public void recordAcknowledged(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        cache.compute(cacheKey, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            return new FrequencyStateEntry(FrequencyState.NORMAL, 0, entry.lastNotifiedAt());
        });
        persistState(typeId, subjectId);
        log.debug("记录确认: typeId={}, subjectId={}, 恢复到 NORMAL", typeId, subjectId);
    }

    /**
     * 更新最后通知时间。
     *
     * @param typeId    类型标识
     * @param subjectId 主体标识（可选）
     */
    public void updateLastNotified(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        cache.compute(cacheKey, (k, existing) -> {
            var entry = existing != null ? existing : FrequencyStateEntry.initial();
            return new FrequencyStateEntry(entry.state(), entry.consecutiveIgnoreCount(), Instant.now());
        });
        persistState(typeId, subjectId);
    }

    /**
     * 解析冷却时间（分钟）。
     *
     * <p>优先从 NotificationTypeRegistry 获取默认值，未找到时使用硬编码默认值。</p>
     *
     * @param typeId 类型标识
     * @return 冷却时间（分钟）
     */
    private int resolveCooldownMinutes(String typeId) {
        return typeRegistry.resolve(typeId)
                .map(NotificationTypeDefinition::defaultCooldownMinutes)
                .orElse(DEFAULT_COOLDOWN_MINUTES);
    }

    /**
     * 持久化指定类型和主体的频率状态到 SQLite。失败时仅记录 WARN 日志。
     */
    private void persistState(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        var entry = cache.getOrDefault(cacheKey, FrequencyStateEntry.initial());
        var effectiveSubjectId = subjectId != null && !subjectId.isEmpty() ? subjectId : "";
        try {
            jdbcTemplate.update("""
                INSERT INTO frequency_states (notification_type, subject_id, state, consecutive_ignore_count, last_notified_at, updated_at)
                VALUES (?, ?, ?, ?, ?, datetime('now'))
                ON CONFLICT(notification_type, subject_id) DO UPDATE SET
                    state = excluded.state,
                    consecutive_ignore_count = excluded.consecutive_ignore_count,
                    last_notified_at = excluded.last_notified_at,
                    updated_at = datetime('now')
                """,
                    typeId,
                    effectiveSubjectId,
                    entry.state().name(),
                    entry.consecutiveIgnoreCount(),
                    entry.lastNotifiedAt().equals(Instant.EPOCH) ? null : entry.lastNotifiedAt().toString()
            );
        } catch (Exception e) {
            log.warn("频率状态持久化失败: typeId={}, subjectId={}, error={}", typeId, subjectId, e.getMessage());
        }
    }
}
