package com.lifepilot.agent.proactive.config;

import com.lifepilot.agent.proactive.model.NotificationType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * 主动推理引擎配置属性。
 *
 * <p>外部化所有业务可调参数，前缀 {@code lifepilot.agent.proactive}。
 * 默认值与架构文档一致。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.agent.proactive")
public class ProactiveConfigProperties {

    /** 是否启用主动推理。 */
    private boolean enabled = true;

    /** 推理间隔（毫秒），默认 30 分钟。 */
    private long intervalMs = 1_800_000;

    /** 免打扰开始小时（0-23），默认 22。 */
    private int quietHoursStart = 22;

    /** 免打扰结束小时（0-23），默认 8。 */
    private int quietHoursEnd = 8;

    /** 各通知类型独立冷却时间（分钟）。 */
    private Map<NotificationType, Integer> cooldownMinutesPerType = new EnumMap<>(Map.of(
            NotificationType.DEADLINE_REMINDER, 60,
            NotificationType.SCHEDULE_REMINDER, 30,
            NotificationType.HABIT_REMINDER, 120,
            NotificationType.STREAK_AT_RISK, 240,
            NotificationType.DAILY_SUMMARY, 1440,
            NotificationType.WEEKLY_REVIEW, 10080
    ));

    /** 每日总结触发小时（0-23），默认 21 点。 */
    private int dailySummaryHour = 21;

    /** 每周回顾触发星期（1=Monday, 7=Sunday），默认周日。 */
    private int weeklyReviewDay = 7;

    /** 每周回顾触发小时（0-23），默认 10 点。 */
    private int weeklyReviewHour = 10;

    /** 通知 SSE 端点超时（毫秒），默认 30 分钟。 */
    private long notificationSseTimeoutMs = 1_800_000;

    /** 用户响应窗口（分钟），默认 30。 */
    private int responseWindowMinutes = 30;

    /** 连续忽略阈值（触发降频），默认 3。 */
    private int ignoreThreshold = 3;

    /** REDUCED 状态冷却期倍数，默认 3。 */
    private int reducedMultiplier = 3;

    /** LLM 生成通知内容最大字符数，默认 100。 */
    private int maxContentLength = 100;

    /** 各 NotificationType 的启用状态，默认全部启用。 */
    private Map<NotificationType, Boolean> typeEnabled = new EnumMap<>(NotificationType.class);

    // ─── getter / setter ───

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

    public int getQuietHoursStart() { return quietHoursStart; }
    public void setQuietHoursStart(int quietHoursStart) { this.quietHoursStart = quietHoursStart; }

    public int getQuietHoursEnd() { return quietHoursEnd; }
    public void setQuietHoursEnd(int quietHoursEnd) { this.quietHoursEnd = quietHoursEnd; }

    public Map<NotificationType, Integer> getCooldownMinutesPerType() { return cooldownMinutesPerType; }
    public void setCooldownMinutesPerType(Map<NotificationType, Integer> cooldownMinutesPerType) {
        this.cooldownMinutesPerType = cooldownMinutesPerType;
    }

    /**
     * 获取指定通知类型的冷却时间（分钟），未配置时 fallback 120 分钟。
     *
     * @param type 通知类型
     * @return 冷却时间（分钟）
     */
    public int getCooldownMinutesForType(NotificationType type) {
        return cooldownMinutesPerType.getOrDefault(type, 120);
    }

    public int getDailySummaryHour() { return dailySummaryHour; }
    public void setDailySummaryHour(int dailySummaryHour) { this.dailySummaryHour = dailySummaryHour; }

    public int getWeeklyReviewDay() { return weeklyReviewDay; }
    public void setWeeklyReviewDay(int weeklyReviewDay) { this.weeklyReviewDay = weeklyReviewDay; }

    public int getWeeklyReviewHour() { return weeklyReviewHour; }
    public void setWeeklyReviewHour(int weeklyReviewHour) { this.weeklyReviewHour = weeklyReviewHour; }

    public long getNotificationSseTimeoutMs() { return notificationSseTimeoutMs; }
    public void setNotificationSseTimeoutMs(long notificationSseTimeoutMs) { this.notificationSseTimeoutMs = notificationSseTimeoutMs; }

    public int getResponseWindowMinutes() { return responseWindowMinutes; }
    public void setResponseWindowMinutes(int responseWindowMinutes) { this.responseWindowMinutes = responseWindowMinutes; }

    public int getIgnoreThreshold() { return ignoreThreshold; }
    public void setIgnoreThreshold(int ignoreThreshold) { this.ignoreThreshold = ignoreThreshold; }

    public int getReducedMultiplier() { return reducedMultiplier; }
    public void setReducedMultiplier(int reducedMultiplier) { this.reducedMultiplier = reducedMultiplier; }

    public int getMaxContentLength() { return maxContentLength; }
    public void setMaxContentLength(int maxContentLength) { this.maxContentLength = maxContentLength; }

    public Map<NotificationType, Boolean> getTypeEnabled() { return typeEnabled; }
    public void setTypeEnabled(Map<NotificationType, Boolean> typeEnabled) { this.typeEnabled = typeEnabled; }

    /**
     * 判断指定通知类型是否启用。
     *
     * @param type 通知类型
     * @return 是否启用（默认 true）
     */
    public boolean isTypeEnabled(NotificationType type) {
        return typeEnabled.getOrDefault(type, true);
    }
}
