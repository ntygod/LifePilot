package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.TemporalEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * FIFO 遗忘策略 — 淘汰超过最大保留天数的实体。
 *
 * <p>按创建时间排序，最早创建的实体优先被遗忘。
 * 仅选择 {@code createdAt} 距今超过 {@code maxRetentionDays} 的实体。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class FifoPolicy implements ForgettingPolicy {

    private final MemoryProperties.Forgetting config;

    public FifoPolicy(MemoryProperties.Forgetting config) {
        this.config = config;
    }

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        var now = Instant.now();
        var maxRetentionDays = config.getMaxRetentionDays();

        return candidates.stream()
                // 过滤超过最大保留天数的实体
                .filter(entity -> Duration.between(entity.createdAt(), now).toDays() > maxRetentionDays)
                // 按创建时间升序排序（最早创建的优先）
                .sorted(Comparator.comparing(TemporalEntity::createdAt))
                // 最多返回 budget 个
                .limit(budget)
                .toList();
    }

    @Override
    public String name() {
        return "FIFO";
    }
}
