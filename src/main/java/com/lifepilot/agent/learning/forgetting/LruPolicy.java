package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * LRU 遗忘策略 — 淘汰超过阈值天数未访问且 accessCount = 0 的实体。
 *
 * <p>按最后访问时间排序，{@code lastAccessedAt} 为 null 的实体排在最前面（从未被访问过），
 * 其次按 {@code lastAccessedAt} 升序排列（最久未访问的优先）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class LruPolicy implements ForgettingPolicy {

    private final AgentLearningProperties.Forgetting config;

    public LruPolicy(AgentLearningProperties.Forgetting config) {
        this.config = config;
    }

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        var now = Instant.now();
        var lruThresholdDays = config.getLruThresholdDays();

        return candidates.stream()
                // 过滤：accessCount == 0 且（lastAccessedAt 为 null 或距今超过 lruThresholdDays）
                .filter(entity -> entity.accessCount() == 0)
                .filter(entity -> entity.lastAccessedAt() == null
                        || Duration.between(entity.lastAccessedAt(), now).toDays() > lruThresholdDays)
                // 按 lastAccessedAt 升序排序（null 排最前面，然后最久未访问的优先）
                .sorted(Comparator.comparing(
                        TemporalEntity::lastAccessedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                // 最多返回 budget 个
                .limit(budget)
                .toList();
    }

    @Override
    public String name() {
        return "LRU";
    }
}
