package com.lifepilot.skill.activation;

import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 激活指标追踪器 — 记录每个 Skill 的激活次数和最近激活时间。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储指标，通过 {@code compute()} 实现线程安全更新。
 * 每次记录都会创建新的 {@link SkillMetrics} 实例（record 不可变）。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillMetricsTracker {

    private static final Logger log = LoggerFactory.getLogger(SkillMetricsTracker.class);

    private final ConcurrentHashMap<String, SkillMetrics> metricsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> activationUpdateFailures = new ConcurrentHashMap<>();

    /**
     * 记录一次 Skill 激活。
     *
     * @param skillId Skill ID
     */
    public void recordActivation(String skillId) {
        metricsMap.compute(skillId, (key, existing) -> {
            long count = (existing != null) ? existing.totalActivations() + 1 : 1;
            return new SkillMetrics(count, Instant.now());
        });
        log.debug("记录 Skill 激活: skillId={}", skillId);
    }

    /**
     * 记录一次 last_activated_at 异步更新失败 — 用于诊断激活计数与 DB 失同步的程度。
     *
     * @param skillId Skill ID
     */
    public void recordActivationUpdateFailure(String skillId) {
        activationUpdateFailures.merge(skillId, 1L, Long::sum);
    }

    /**
     * 获取指定 Skill 的更新失败次数。
     *
     * @param skillId Skill ID
     * @return 失败次数，无记录时返回 0
     */
    public long getActivationUpdateFailures(String skillId) {
        return activationUpdateFailures.getOrDefault(skillId, 0L);
    }

    /**
     * 获取指定 Skill 的指标。
     *
     * @param skillId Skill ID
     * @return 指标快照，无记录时返回 empty
     */
    public Optional<SkillMetrics> getMetrics(String skillId) {
        return Optional.ofNullable(metricsMap.get(skillId));
    }

    /**
     * 获取指定 Skill 的总激活次数。
     *
     * @param skillId Skill ID
     * @return 总激活次数，无记录时返回 0
     */
    public long getActivationCount(String skillId) {
        SkillMetrics metrics = metricsMap.get(skillId);
        return metrics != null ? metrics.totalActivations() : 0;
    }

    /**
     * Skill 激活指标快照 — 不可变 record。
     *
     * @param totalActivations 总激活次数
     * @param lastActivatedAt  最近一次激活时间
     */
    public record SkillMetrics(
            long totalActivations,
            @Nullable Instant lastActivatedAt
    ) {}
}
