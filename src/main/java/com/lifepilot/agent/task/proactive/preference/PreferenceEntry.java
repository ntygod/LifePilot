package com.lifepilot.agent.task.proactive.preference;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 偏好条目记录。
 *
 * @param userId           用户 ID
 * @param dimension        偏好维度
 * @param preferenceKey    偏好键（如 TIMING 维度下的 "morning"/"afternoon"/"evening"）
 * @param preferenceValue  偏好值 [0, 1]，0.5 为中性
 * @param observationCount 观察次数
 * @param lastObservedAt   上次观察时间
 * @param updatedAt        更新时间
 * @author zsg
 * @since 2026-04-14
 */
public record PreferenceEntry(
        String userId,
        PreferenceDimension dimension,
        String preferenceKey,
        float preferenceValue,
        int observationCount,
        @Nullable Instant lastObservedAt,
        Instant updatedAt
) {
    public PreferenceEntry {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(dimension, "dimension 不能为空");
        Objects.requireNonNull(preferenceKey, "preferenceKey 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        preferenceValue = Math.max(0f, Math.min(1f, preferenceValue));
        observationCount = Math.max(0, observationCount);
    }
}
