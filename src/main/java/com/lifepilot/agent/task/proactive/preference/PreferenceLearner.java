package com.lifepilot.agent.task.proactive.preference;

import com.lifepilot.agent.task.proactive.DeliveryResult;
import com.lifepilot.agent.task.proactive.ProactiveAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 偏好学习器 — 从行为反馈中自动学习五维偏好。
 *
 * <p>每次投递后调用 {@link #learnFromDelivery}，根据投递时间、行为类型、结果
 * 更新五维偏好值。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class PreferenceLearner {

    private static final Logger log = LoggerFactory.getLogger(PreferenceLearner.class);

    private final PreferenceRepository preferenceRepository;

    public PreferenceLearner(PreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /** 获取偏好仓储（供引擎评分调整使用）。 */
    public PreferenceRepository getPreferenceRepository() {
        return preferenceRepository;
    }

    /**
     * 从一次投递结果中学习偏好。
     *
     * @param action 投递的动作
     * @param result 投递结果
     * @param positive 用户反馈是否正面（true=有用/采纳，false=忽略/不相关）
     */
    public void learnFromDelivery(ProactiveAction action, DeliveryResult result,
                                   String userId, boolean positive) {
        float signal = positive ? 0.8f : 0.2f;

        // 时间维度：记录投递时间段的偏好
        String timeSlot = resolveTimeSlot(result.deliveredAt(), ZoneId.systemDefault());
        preferenceRepository.observe(userId, PreferenceDimension.TIMING, timeSlot, signal);

        // 领域维度：记录行为类型的偏好
        preferenceRepository.observe(userId, PreferenceDimension.DOMAIN,
                action.candidate().behaviorName(), signal);

        // 风格维度：记录投递级别的偏好
        preferenceRepository.observe(userId, PreferenceDimension.STYLE,
                result.level().name(), signal);

        log.debug("偏好学习: userId={}, behavior={}, timeSlot={}, positive={}",
                userId, action.candidate().behaviorName(), timeSlot, positive);
    }

    /** 将时间映射为时段标签。 */
    private static String resolveTimeSlot(java.time.Instant instant, ZoneId zoneId) {
        int hour = LocalTime.ofInstant(instant, zoneId).getHour();
        if (hour >= 6 && hour < 9) return "early-morning";
        if (hour >= 9 && hour < 12) return "morning";
        if (hour >= 12 && hour < 14) return "noon";
        if (hour >= 14 && hour < 18) return "afternoon";
        if (hour >= 18 && hour < 21) return "evening";
        return "night";
    }
}
