package com.lifepilot.agent.proactive.model;

/**
 * 频率状态枚举 — 三态状态机。
 *
 * <p>每个 {@link NotificationType} 独立维护一个频率状态。
 * 降频是渐进的（NORMAL → REDUCED → MUTED），恢复是即时的（任意状态 → NORMAL）。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum FrequencyState {

    /** 正常状态：所有紧急度均发送。 */
    NORMAL,

    /** 降频状态：仅 HIGH/MEDIUM，冷却期乘以倍数。 */
    REDUCED,

    /** 静默状态：仅 HIGH urgency 时发送。 */
    MUTED;

    /**
     * 用户忽略时的状态转换。
     *
     * @param consecutiveIgnoreCount 连续忽略次数
     * @param threshold              降频阈值
     * @return 新的频率状态
     */
    public FrequencyState onIgnored(int consecutiveIgnoreCount, int threshold) {
        return switch (this) {
            case NORMAL  -> consecutiveIgnoreCount >= threshold ? REDUCED : NORMAL;
            case REDUCED -> consecutiveIgnoreCount >= threshold ? MUTED : REDUCED;
            case MUTED   -> MUTED;
        };
    }

    /**
     * 用户确认时即时恢复到 NORMAL。
     *
     * @return 始终返回 NORMAL
     */
    public FrequencyState onAcknowledged() {
        return NORMAL;
    }

    /**
     * 判断当前状态是否允许发送指定紧急度的通知。
     *
     * @param urgency 紧急程度
     * @return 是否允许发送
     */
    public boolean shouldSend(Urgency urgency) {
        return switch (this) {
            case NORMAL  -> true;
            case REDUCED -> urgency == Urgency.HIGH || urgency == Urgency.MEDIUM;
            case MUTED   -> urgency == Urgency.HIGH;
        };
    }

    /**
     * 获取冷却期倍数。
     *
     * @param reducedMultiplier REDUCED 状态的倍数（从配置读取）
     * @return 冷却期倍数
     */
    public int intervalMultiplier(int reducedMultiplier) {
        return switch (this) {
            case NORMAL  -> 1;
            case REDUCED -> reducedMultiplier;
            case MUTED   -> Integer.MAX_VALUE;
        };
    }
}
