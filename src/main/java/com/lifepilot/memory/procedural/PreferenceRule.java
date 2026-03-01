package com.lifepilot.memory.procedural;

import java.time.Instant;

/**
 * 偏好规则 — 从用户行为中学习到的个人偏好。
 *
 * <p>按类别组织，带置信度评分。同一 {@code category} 和 {@code key} 组合下
 * 偏好规则唯一，重复观察会递增 {@code observationCount} 并提升 {@code confidence}。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record PreferenceRule(
        String ruleId,
        String category,
        String key,
        String value,
        float confidence,
        String learnedFrom,
        int observationCount,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 判断偏好规则是否高置信度。
     *
     * @return 当 confidence >= 0.7f 时返回 true
     */
    public boolean isHighConfidence() {
        return confidence >= 0.7f;
    }
}
