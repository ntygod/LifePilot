package com.lifepilot.interaction.middleware.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 基于用户历史行为的信任分数计算器。
 * <p>
 * 查询 {@code user_behavior} 表获取交互次数、安全事件次数、账户年龄、最近事件时间，
 * 综合计算信任分数。查询失败时降级返回默认分数 0.5。
 * <p>
 * 计算公式：
 * <ul>
 *   <li>基础分 = 0.5</li>
 *   <li>+ 交互次数贡献：min(totalInteractions / 100.0, 0.2)</li>
 *   <li>- 安全事件惩罚：min(securityIncidents * 0.1, 0.3)</li>
 *   <li>+ 账户年龄贡献：min(accountAgeDays / 365.0, 0.1)</li>
 *   <li>- 最近事件衰减：如果 lastIncident 在 7 天内，额外 -0.1</li>
 *   <li>结果 clamp 到 [0.0, 1.0]</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TrustScoreCalculator {

    private static final Logger log = LoggerFactory.getLogger(TrustScoreCalculator.class);

    /** 基础信任分数 */
    private static final double BASE_SCORE = 0.5;

    /** 查询失败时的默认降级分数 */
    private static final double DEFAULT_SCORE = 0.5;

    /** 交互次数贡献上限 */
    private static final double MAX_INTERACTION_CONTRIBUTION = 0.2;

    /** 交互次数贡献基数（达到此数量获得最大贡献） */
    private static final double INTERACTION_BASE = 100.0;

    /** 每次安全事件的惩罚分数 */
    private static final double INCIDENT_PENALTY_PER = 0.1;

    /** 安全事件惩罚上限 */
    private static final double MAX_INCIDENT_PENALTY = 0.3;

    /** 账户年龄贡献上限 */
    private static final double MAX_AGE_CONTRIBUTION = 0.1;

    /** 账户年龄贡献基数（天数，达到此天数获得最大贡献） */
    private static final double AGE_BASE_DAYS = 365.0;

    /** 最近事件衰减阈值（天） */
    private static final long RECENT_INCIDENT_THRESHOLD_DAYS = 7;

    /** 最近事件额外惩罚 */
    private static final double RECENT_INCIDENT_DECAY = 0.1;

    private final JdbcTemplate jdbcTemplate;

    public TrustScoreCalculator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 计算指定用户的信任分数。
     *
     * @param userId 用户 ID
     * @return 信任分数，范围 [0.0, 1.0]；查询失败时返回默认分数 0.5
     */
    public double calculate(String userId) {
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT total_interactions, security_incidents, last_incident_at, first_seen_at "
                            + "FROM user_behavior WHERE user_id = ?",
                    userId
            );

            if (rows.isEmpty()) {
                // 无行为记录的新用户，返回默认分数
                return DEFAULT_SCORE;
            }

            var row = rows.getFirst();
            int totalInteractions = ((Number) row.get("total_interactions")).intValue();
            int securityIncidents = ((Number) row.get("security_incidents")).intValue();
            String lastIncidentAt = (String) row.get("last_incident_at");
            String firstSeenAt = (String) row.get("first_seen_at");

            // 交互次数贡献
            double interactionContribution = Math.min(totalInteractions / INTERACTION_BASE, MAX_INTERACTION_CONTRIBUTION);

            // 安全事件惩罚
            double incidentPenalty = Math.min(securityIncidents * INCIDENT_PENALTY_PER, MAX_INCIDENT_PENALTY);

            // 账户年龄贡献
            double ageContribution = 0.0;
            if (firstSeenAt != null) {
                long ageDays = ChronoUnit.DAYS.between(Instant.parse(firstSeenAt), Instant.now());
                ageContribution = Math.min(ageDays / AGE_BASE_DAYS, MAX_AGE_CONTRIBUTION);
            }

            // 最近事件衰减
            double recentDecay = 0.0;
            if (lastIncidentAt != null) {
                long daysSinceIncident = ChronoUnit.DAYS.between(Instant.parse(lastIncidentAt), Instant.now());
                if (daysSinceIncident < RECENT_INCIDENT_THRESHOLD_DAYS) {
                    recentDecay = RECENT_INCIDENT_DECAY;
                }
            }

            // 综合计算并 clamp 到 [0.0, 1.0]
            double score = BASE_SCORE + interactionContribution - incidentPenalty + ageContribution - recentDecay;
            return Math.max(0.0, Math.min(1.0, score));

        } catch (Exception e) {
            log.warn("信任分数计算失败，降级返回默认分数: userId={}", userId, e);
            return DEFAULT_SCORE;
        }
    }
}
