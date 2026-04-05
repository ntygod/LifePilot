package com.lifepilot.agent.task.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 信任梯度管理器 — 维护 (userId, candidateType) 的信任等级。
 *
 * <p>升级规则：连续 N 次正面反馈后自动升级。
 * 降级规则：一次 DISMISSED 降一级，NOT_RELEVANT 降到 OBSERVE + 冷却 7 天。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class ReminderTrustGradient {

    private static final Logger log = LoggerFactory.getLogger(ReminderTrustGradient.class);

    /** 连续正面反馈升级所需次数。 */
    private static final int POSITIVE_THRESHOLD = 5;

    /** NOT_RELEVANT 冷却天数。 */
    private static final int NOT_RELEVANT_COOLDOWN_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;

    public ReminderTrustGradient(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
    }

    /**
     * 获取指定用户和候选类型的信任等级。
     *
     * <p>如果处于冷却期则返回 OBSERVE。</p>
     */
    public ReminderTrustLevel getTrustLevel(String userId, ReminderCandidateType candidateType) {
        String sql = """
                SELECT trust_level, cooldown_until
                FROM proactive_reminder_trust_levels
                WHERE user_id = ? AND candidate_type = ?
                """;
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return ReminderTrustLevel.NOTIFY;
            }
            String cooldownUntil = rs.getString("cooldown_until");
            if (cooldownUntil != null) {
                Instant cooldown = Instant.parse(cooldownUntil);
                if (Instant.now().isBefore(cooldown)) {
                    return ReminderTrustLevel.OBSERVE;
                }
            }
            try {
                return ReminderTrustLevel.valueOf(rs.getString("trust_level"));
            } catch (IllegalArgumentException e) {
                return ReminderTrustLevel.NOTIFY;
            }
        }, userId, candidateType.name());
    }

    /**
     * 记录正面反馈并在满足连续次数后自动升级。
     */
    public void recordPositiveFeedback(String userId, ReminderCandidateType candidateType) {
        ensureRow(userId, candidateType);
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                UPDATE proactive_reminder_trust_levels
                SET consecutive_positive = consecutive_positive + 1,
                    consecutive_negative = 0,
                    last_feedback_type = 'POSITIVE',
                    updated_at = ?
                WHERE user_id = ? AND candidate_type = ?
                """, now, userId, candidateType.name());

        // 检查是否达到升级阈值
        Integer positiveCount = jdbcTemplate.queryForObject("""
                SELECT consecutive_positive
                FROM proactive_reminder_trust_levels
                WHERE user_id = ? AND candidate_type = ?
                """, Integer.class, userId, candidateType.name());
        if (positiveCount != null && positiveCount >= POSITIVE_THRESHOLD) {
            upgradeIfPossible(userId, candidateType, now);
        }
    }

    /**
     * 记录 DISMISSED 反馈，降一级。
     */
    public void recordDismissedFeedback(String userId, ReminderCandidateType candidateType) {
        ensureRow(userId, candidateType);
        String now = Instant.now().toString();
        ReminderTrustLevel current = getTrustLevel(userId, candidateType);
        ReminderTrustLevel downgraded = downgrade(current);
        jdbcTemplate.update("""
                UPDATE proactive_reminder_trust_levels
                SET trust_level = ?,
                    consecutive_positive = 0,
                    consecutive_negative = consecutive_negative + 1,
                    last_feedback_type = 'DISMISSED',
                    updated_at = ?
                WHERE user_id = ? AND candidate_type = ?
                """, downgraded.name(), now, userId, candidateType.name());
        log.info("信任等级降级: userId={}, type={}, {} -> {}",
                userId, candidateType, current, downgraded);
    }

    /**
     * 记录 NOT_RELEVANT 反馈，降到 OBSERVE + 冷却 7 天。
     */
    public void recordNotRelevantFeedback(String userId, ReminderCandidateType candidateType) {
        ensureRow(userId, candidateType);
        String now = Instant.now().toString();
        String cooldownUntil = Instant.now().plus(NOT_RELEVANT_COOLDOWN_DAYS, ChronoUnit.DAYS).toString();
        jdbcTemplate.update("""
                UPDATE proactive_reminder_trust_levels
                SET trust_level = ?,
                    consecutive_positive = 0,
                    consecutive_negative = consecutive_negative + 1,
                    cooldown_until = ?,
                    last_feedback_type = 'NOT_RELEVANT',
                    updated_at = ?
                WHERE user_id = ? AND candidate_type = ?
                """, ReminderTrustLevel.OBSERVE.name(), cooldownUntil, now,
                userId, candidateType.name());
        log.info("信任等级重置为 OBSERVE 并冷却 {} 天: userId={}, type={}",
                NOT_RELEVANT_COOLDOWN_DAYS, userId, candidateType);
    }

    /** 确保行存在。 */
    private void ensureRow(String userId, ReminderCandidateType candidateType) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO proactive_reminder_trust_levels
                    (user_id, candidate_type, trust_level, consecutive_positive, consecutive_negative, updated_at)
                VALUES (?, ?, ?, 0, 0, ?)
                """, userId, candidateType.name(), ReminderTrustLevel.NOTIFY.name(), now);
    }

    /** 如果达到升级阈值则尝试升级。 */
    private void upgradeIfPossible(String userId, ReminderCandidateType candidateType, String now) {
        ReminderTrustLevel current = getTrustLevel(userId, candidateType);
        ReminderTrustLevel upgraded = upgrade(current);
        if (upgraded != current) {
            jdbcTemplate.update("""
                    UPDATE proactive_reminder_trust_levels
                    SET trust_level = ?, consecutive_positive = 0, cooldown_until = NULL, updated_at = ?
                    WHERE user_id = ? AND candidate_type = ?
                    """, upgraded.name(), now, userId, candidateType.name());
            log.info("信任等级升级: userId={}, type={}, {} -> {}",
                    userId, candidateType, current, upgraded);
        }
    }

    /** 升一级。 */
    private static ReminderTrustLevel upgrade(ReminderTrustLevel current) {
        return switch (current) {
            case OBSERVE -> ReminderTrustLevel.NOTIFY;
            case NOTIFY -> ReminderTrustLevel.PREPARE;
            case PREPARE -> ReminderTrustLevel.AUTO_EXECUTE;
            case AUTO_EXECUTE -> ReminderTrustLevel.AUTO_EXECUTE;
        };
    }

    /** 降一级。 */
    private static ReminderTrustLevel downgrade(ReminderTrustLevel current) {
        return switch (current) {
            case AUTO_EXECUTE -> ReminderTrustLevel.PREPARE;
            case PREPARE -> ReminderTrustLevel.NOTIFY;
            case NOTIFY -> ReminderTrustLevel.OBSERVE;
            case OBSERVE -> ReminderTrustLevel.OBSERVE;
        };
    }
}
