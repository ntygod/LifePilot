package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 行为自主度配置记录。
 *
 * @param userId              用户 ID
 * @param behaviorName        行为插件名称
 * @param autonomyLevel       当前自主度级别
 * @param consecutivePositive 连续正反馈次数
 * @param consecutiveNegative 连续负反馈次数
 * @param upgradeSuggested    是否已建议升级（等待用户确认）
 * @param cooldownUntil       冷却截止时间（降级后的保护期）
 * @param updatedAt           更新时间
 * @author zsg
 * @since 2026-04-14
 */
public record AutonomyConfig(
        String userId,
        String behaviorName,
        AutonomyLevel autonomyLevel,
        int consecutivePositive,
        int consecutiveNegative,
        boolean upgradeSuggested,
        @Nullable Instant cooldownUntil,
        Instant updatedAt
) {
    public AutonomyConfig {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(behaviorName, "behaviorName 不能为空");
        Objects.requireNonNull(autonomyLevel, "autonomyLevel 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        consecutivePositive = Math.max(0, consecutivePositive);
        consecutiveNegative = Math.max(0, consecutiveNegative);
    }

    /** 创建默认配置。 */
    public static AutonomyConfig defaultFor(String userId, String behaviorName, AutonomyLevel defaultLevel) {
        return new AutonomyConfig(userId, behaviorName, defaultLevel, 0, 0, false, null, Instant.now());
    }
}
