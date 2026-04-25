package com.lifepilot.memory.lifecycle.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 负反馈阈值配置 —— 绑定 {@code memory.feedback.*} 前缀下的 N 次计数 / 累计分两条触发线。
 *
 * <p>与同 prefix 下的 {@code like-boost} / {@code dislike-penalty} / {@code expiration-cron}
 * 共享前缀，由 Spring Boot 的 relaxed-binding 自动分派给相应的 {@code @ConfigurationProperties}
 * bean —— 额外增量键不会互相覆盖。</p>
 *
 * <p>默认值：
 * <ul>
 *   <li>{@code negative-threshold-count = 3} —— 连续 3 次负反馈后将实体转 {@code SUPERSEDED}</li>
 *   <li>{@code negative-threshold-score = -1.0} —— 单次 cumulativeScore 低于该阈值直接触发，
 *       无需等待累计次数</li>
 * </ul>
 * 两者"或"关系，任一满足都触发；{@code NegativeFeedbackListener} 读取本配置。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Component
@ConfigurationProperties(prefix = "memory.feedback")
public class FeedbackThresholdConfig {

    /** 负反馈计数阈值：累计出现 N 次负 delta 时触发 SUPERSEDED。 */
    private int negativeThresholdCount = 3;

    /** 累计分阈值：单次 {@code cumulativeScore} 低于该值直接触发 SUPERSEDED。 */
    private double negativeThresholdScore = -1.0;

    public int getNegativeThresholdCount() {
        return negativeThresholdCount;
    }

    public void setNegativeThresholdCount(int negativeThresholdCount) {
        this.negativeThresholdCount = negativeThresholdCount;
    }

    public double getNegativeThresholdScore() {
        return negativeThresholdScore;
    }

    public void setNegativeThresholdScore(double negativeThresholdScore) {
        this.negativeThresholdScore = negativeThresholdScore;
    }
}
