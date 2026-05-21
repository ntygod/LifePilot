package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.time.Duration;
import java.time.Instant;

/**
 * 遗忘优先级计算器 — 综合评分公式计算实体的遗忘优先级。
 *
 * <p>公式：priority = (timeFactor × 0.3 + accessFactor × 0.3 + importanceFactor × 0.2) × privacyFactor</p>
 * <ul>
 *   <li>timeFactor = daysSinceLastAccess / maxRetentionDays（越久越高）</li>
 *   <li>accessFactor = 1.0 / (1 + accessCount)（访问越少越高）</li>
 *   <li>importanceFactor = 1.0 - importanceScore（越不重要越高）</li>
 *   <li>privacyFactor = containsPII ? (1.0 + privacyAwareBoost) : 1.0</li>
 * </ul>
 *
 * <p>优先级越高，越应该被遗忘。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ForgettingPriority {

    private final AgentLearningProperties.Forgetting config;

    public ForgettingPriority(AgentLearningProperties.Forgetting config) {
        this.config = config;
    }

    /**
     * 计算实体的遗忘优先级。
     *
     * @param entity 时序实体
     * @return 遗忘优先级（越高越应被遗忘）
     */
    public float calculate(TemporalEntity entity) {
        // timeFactor: 距上次访问的天数 / 最大保留天数
        long daysSinceLastAccess = entity.lastAccessedAt() != null
                ? Duration.between(entity.lastAccessedAt(), Instant.now()).toDays()
                : Duration.between(entity.createdAt(), Instant.now()).toDays();
        float timeFactor = Math.min(1.0f, (float) daysSinceLastAccess / config.getMaxRetentionDays());

        // accessFactor: 1.0 / (1 + accessCount)
        float accessFactor = 1.0f / (1 + entity.accessCount());

        // importanceFactor: 1.0 - importanceScore
        float importanceFactor = 1.0f - entity.importanceScore();

        // privacyFactor: PII 实体优先清理
        boolean containsPII = hasPiiMarker(entity);
        float privacyFactor = containsPII ? (1.0f + config.getPrivacyAwareBoost()) : 1.0f;

        // 综合评分
        float basePriority = timeFactor * 0.3f + accessFactor * 0.3f + importanceFactor * 0.2f;
        return basePriority * privacyFactor;
    }

    /**
     * 检查实体是否包含 PII 标记。
     * 通过 properties 中的 "pii" 键判断。
     *
     * @param entity 时序实体
     * @return 是否包含 PII 标记
     */
    private boolean hasPiiMarker(TemporalEntity entity) {
        var piiValue = entity.properties().get("pii");
        return piiValue != null && Boolean.parseBoolean(piiValue.toString());
    }
}
