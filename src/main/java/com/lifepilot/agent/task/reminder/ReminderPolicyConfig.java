package com.lifepilot.agent.task.reminder;

import java.time.Duration;

/**
 * 提醒策略配置。
 *
 * <p>当前为算法层纯 Java 配置模型，
 * 后续可映射到 Spring {@code @ConfigurationProperties}。</p>
 *
 * @param dueSoonThresholdHours           截止临近阈值
 * @param commitmentGapThresholdHours     承诺缺口阈值
 * @param dailyMaxReminders               每日最大主动提醒数
 * @param defaultCooldownHours            同主题默认冷却时间
 * @param preferredWindowLookaheadMinutes 习惯窗口前瞻时长
 * @param minFinalScore                   触发最小分
 * @param softPushThreshold               轻提醒阈值
 * @param strongPushThreshold             标准提醒阈值
 * @param anomalyThreshold                异常检测阈值
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderPolicyConfig(
        int dueSoonThresholdHours,
        int commitmentGapThresholdHours,
        int dailyMaxReminders,
        int defaultCooldownHours,
        int preferredWindowLookaheadMinutes,
        float minFinalScore,
        float softPushThreshold,
        float strongPushThreshold,
        float anomalyThreshold
) {

    public ReminderPolicyConfig() {
        this(24, 18, 3, 24, 60, 0.55f, 0.63f, 0.78f, 0.65f);
    }

    public ReminderPolicyConfig {
        dueSoonThresholdHours = Math.max(1, dueSoonThresholdHours);
        commitmentGapThresholdHours = Math.max(1, commitmentGapThresholdHours);
        dailyMaxReminders = Math.max(1, dailyMaxReminders);
        defaultCooldownHours = Math.max(1, defaultCooldownHours);
        preferredWindowLookaheadMinutes = Math.max(1, preferredWindowLookaheadMinutes);
        minFinalScore = clamp(minFinalScore);
        softPushThreshold = clamp(softPushThreshold);
        strongPushThreshold = clamp(strongPushThreshold);
        anomalyThreshold = clamp(anomalyThreshold);
    }

    public Duration defaultCooldown() {
        return Duration.ofHours(defaultCooldownHours);
    }

    public Duration preferredWindowLookahead() {
        return Duration.ofMinutes(preferredWindowLookaheadMinutes);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
