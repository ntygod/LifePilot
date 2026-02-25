package com.lifepilot.skill.activation;

import com.lifepilot.skill.model.SubAgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 激活指标追踪器 — 记录每个 Skill 的使用频率、成功率和资源消耗。
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

    /**
     * 记录一次 Skill 激活结果。
     *
     * @param skillId Skill ID
     * @param result  SubAgent 执行结果
     */
    public void record(String skillId, SubAgentResult result) {
        metricsMap.compute(skillId, (key, existing) -> {
            if (existing == null) {
                // 首次记录，初始化指标
                return new SkillMetrics(
                        1,
                        result.success() ? 1 : 0,
                        result.success() ? 0 : 1,
                        result.tokensUsed(),
                        result.durationMs()
                );
            }
            // 累加更新指标
            return new SkillMetrics(
                    existing.totalActivations() + 1,
                    existing.successCount() + (result.success() ? 1 : 0),
                    existing.failureCount() + (result.success() ? 0 : 1),
                    existing.totalTokensUsed() + result.tokensUsed(),
                    existing.totalDurationMs() + result.durationMs()
            );
        });
        log.debug("记录 Skill 指标: skillId={}, success={}", skillId, result.success());
    }

    /**
     * 获取指定 Skill 的成功率。
     *
     * @param skillId Skill ID
     * @return 成功率（0.0-1.0），无记录时返回 0.0
     */
    public double getSuccessRate(String skillId) {
        SkillMetrics metrics = metricsMap.get(skillId);
        if (metrics == null || metrics.totalActivations() == 0) {
            return 0.0;
        }
        return (double) metrics.successCount() / metrics.totalActivations();
    }

    /**
     * 获取指定 Skill 的完整指标。
     *
     * @param skillId Skill ID
     * @return 指标快照，无记录时返回 empty
     */
    public Optional<SkillMetrics> getMetrics(String skillId) {
        return Optional.ofNullable(metricsMap.get(skillId));
    }

    /**
     * Skill 激活指标快照 — 不可变 record。
     *
     * @param totalActivations 总激活次数
     * @param successCount     成功次数
     * @param failureCount     失败次数
     * @param totalTokensUsed  总 Token 消耗
     * @param totalDurationMs  总执行时间（毫秒）
     */
    public record SkillMetrics(
            long totalActivations,
            long successCount,
            long failureCount,
            long totalTokensUsed,
            long totalDurationMs
    ) {}
}
