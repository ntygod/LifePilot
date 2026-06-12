package com.lifepilot.memory.consumption;

import com.lifepilot.memory.store.entity.TemporalEntity;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆实体排序统一口径 —— 供 L3.5 热摘要与 {@code memory(action="search-experience")} 共用，
 * 使同一实体在"自动注入"与"主动召回"中得到一致的相对优先级。
 *
 * <p>评分 = 可信度 × 0.45 + 重要度 × 0.35 + 新近度 × 0.20。新近度按 updatedAt 距今天数
 * 在 30 天内线性衰减（缺 updatedAt 视为最新）。</p>
 *
 * <p>注：L4 程序模板匹配（{@code IntentMatcher}，语义+关键词融合 + 模板可靠度）服务的是
 * 模板匹配而非实体排序，语义不同，不并入本口径。</p>
 *
 * @author zsg
 * @since 2026-06-12
 */
public final class ExperienceRanking {

    private static final double RECENCY_DECAY_DAYS = 30.0;

    private ExperienceRanking() {
    }

    /**
     * 计算实体排序分。
     *
     * @param entity 记忆实体
     * @param now    当前时刻（用于新近度衰减计算）
     * @return 排序分，越大优先级越高
     */
    public static double score(TemporalEntity entity, Instant now) {
        if (entity == null) {
            return 0.0;
        }
        double trust = entity.trustScore();
        double importance = entity.importanceScore();
        double recency = 1.0;
        if (entity.updatedAt() != null && now != null) {
            long days = Math.max(0, Duration.between(entity.updatedAt(), now).toDays());
            recency = Math.max(0.0, 1.0 - days / RECENCY_DECAY_DAYS);
        }
        return trust * 0.45 + importance * 0.35 + recency * 0.20;
    }
}
