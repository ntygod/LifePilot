package com.lifepilot.agent.task.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主动提醒反馈仓储。
 *
 * <p>负责保存通知级反馈、主题静默偏好，并提供 topic state 所需的统计读取。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderFeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveFeedback(ReminderFeedbackRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_feedback (
                    id, notification_id, user_id, topic_key, feedback_type, comment,
                    insight_entity_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(notification_id) DO UPDATE SET
                    user_id = excluded.user_id,
                    topic_key = excluded.topic_key,
                    feedback_type = excluded.feedback_type,
                    comment = excluded.comment,
                    insight_entity_id = excluded.insight_entity_id,
                    updated_at = excluded.updated_at
                """,
                record.id(),
                record.notificationId(),
                record.userId(),
                record.topicKey(),
                record.feedbackType().name(),
                record.comment(),
                record.insightEntityId(),
                record.createdAt().toString(),
                record.updatedAt().toString()
        );
    }

    /**
     * 反查某条 notification 关联的 L3 {@code proactive_insight_*} 实体 ID 列表
     * —— 供 {@link com.lifepilot.agent.task.proactive.TrustUpgradeService} 在用户
     * 标记提醒为"无用"时溯源到 insight 实体，做 importanceScore 惩罚。
     *
     * <p>过滤条件：
     * <ul>
     *   <li>{@code notification_id = ?}</li>
     *   <li>{@code insight_entity_id IS NOT NULL} —— 老反馈 / 非 insight 来源的提醒无关联</li>
     * </ul>
     *
     * <p>正常情况下每条 notification 至多一条 feedback 行，返回列表可能 0 或 1 项；
     * 若未来扩展为多关联也能自动兼容。</p>
     *
     * @param notificationId 通知 ID
     * @return insight 实体 ID 列表（可能为空，保证非 null）
     */
    public List<String> findInsightEntityIdsByNotification(String notificationId) {
        return jdbcTemplate.queryForList(
                """
                SELECT insight_entity_id
                FROM proactive_reminder_feedback
                WHERE notification_id = ? AND insight_entity_id IS NOT NULL
                """,
                String.class, notificationId);
    }

    public Optional<ReminderNotificationFeedbackView> findFeedbackViewByNotificationId(String notificationId) {
        List<ReminderNotificationFeedbackView> rows = jdbcTemplate.query("""
                SELECT f.notification_id,
                       f.topic_key,
                       f.feedback_type,
                       f.comment,
                       f.updated_at,
                       COALESCE(p.muted, 0) AS topic_muted
                FROM proactive_reminder_feedback f
                LEFT JOIN proactive_reminder_topic_preferences p
                  ON p.user_id = f.user_id AND p.topic_key = f.topic_key
                WHERE f.notification_id = ?
                """, this::mapFeedbackView, notificationId);
        return rows.stream().findFirst();
    }

    public Map<String, ReminderNotificationFeedbackView> findFeedbackViewsByNotificationIds(List<String> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(notificationIds.size(), "?"));
        List<ReminderNotificationFeedbackView> rows = jdbcTemplate.query("""
                SELECT f.notification_id,
                       f.topic_key,
                       f.feedback_type,
                       f.comment,
                       f.updated_at,
                       COALESCE(p.muted, 0) AS topic_muted
                FROM proactive_reminder_feedback f
                LEFT JOIN proactive_reminder_topic_preferences p
                  ON p.user_id = f.user_id AND p.topic_key = f.topic_key
                WHERE f.notification_id IN (%s)
                """.formatted(placeholders), this::mapFeedbackView, notificationIds.toArray());
        Map<String, ReminderNotificationFeedbackView> result = new LinkedHashMap<>();
        for (ReminderNotificationFeedbackView row : rows) {
            result.put(row.notificationId(), row);
        }
        return result;
    }

    public Map<String, ReminderTopicFeedbackStats> summarizeTopicStatsByUserIdSince(String userId, Instant since) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT topic_key,
                       SUM(CASE WHEN feedback_type = 'ACTED' THEN 1 ELSE 0 END) AS acted_count,
                       SUM(CASE WHEN feedback_type = 'SNOOZED' THEN 1 ELSE 0 END) AS snoozed_count,
                       SUM(CASE WHEN feedback_type = 'DISMISSED' THEN 1 ELSE 0 END) AS dismissed_count,
                       SUM(CASE WHEN feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END) AS not_relevant_count
                FROM proactive_reminder_feedback
                WHERE user_id = ? AND updated_at >= ?
                GROUP BY topic_key
                """, userId, since.toString());
        Map<String, ReminderTopicFeedbackStats> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String topicKey = row.get("topic_key").toString();
            result.put(topicKey, new ReminderTopicFeedbackStats(
                    asInt(row.get("acted_count")),
                    asInt(row.get("snoozed_count")),
                    asInt(row.get("dismissed_count")),
                    asInt(row.get("not_relevant_count")),
                    false
            ));
        }

        List<Map<String, Object>> preferenceRows = jdbcTemplate.queryForList("""
                SELECT topic_key, muted
                FROM proactive_reminder_topic_preferences
                WHERE user_id = ?
                """, userId);
        for (Map<String, Object> row : preferenceRows) {
            String topicKey = row.get("topic_key").toString();
            boolean muted = asInt(row.get("muted")) == 1;
            ReminderTopicFeedbackStats existing = result.getOrDefault(topicKey, ReminderTopicFeedbackStats.empty());
            result.put(topicKey, new ReminderTopicFeedbackStats(
                    existing.actedCount30d(),
                    existing.snoozedCount30d(),
                    existing.dismissedCount30d(),
                    existing.notRelevantCount30d(),
                    muted
            ));
        }
        return result;
    }

    public ReminderUserFeedbackSummary summarizeUserFeedbackByUserIdSince(String userId, Instant since) {
        Map<String, Object> feedbackRow = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(CASE WHEN feedback_type = 'ACTED' THEN 1 ELSE 0 END), 0) AS acted_count,
                       COALESCE(SUM(CASE WHEN feedback_type = 'SNOOZED' THEN 1 ELSE 0 END), 0) AS snoozed_count,
                       COALESCE(SUM(CASE WHEN feedback_type = 'DISMISSED' THEN 1 ELSE 0 END), 0) AS dismissed_count,
                       COALESCE(SUM(CASE WHEN feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END), 0) AS not_relevant_count
                FROM proactive_reminder_feedback
                WHERE user_id = ? AND updated_at >= ?
                """, userId, since.toString());
        Integer mutedTopicCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM proactive_reminder_topic_preferences
                WHERE user_id = ? AND muted = 1
                """, Integer.class, userId);
        return new ReminderUserFeedbackSummary(
                asInt(feedbackRow.get("acted_count")),
                asInt(feedbackRow.get("snoozed_count")),
                asInt(feedbackRow.get("dismissed_count")),
                asInt(feedbackRow.get("not_relevant_count")),
                mutedTopicCount != null ? mutedTopicCount : 0
        );
    }

    public void upsertTopicPreference(ReminderTopicPreferenceRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_topic_preferences (
                    user_id, topic_key, muted, muted_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id, topic_key) DO UPDATE SET
                    muted = excluded.muted,
                    muted_at = excluded.muted_at,
                    updated_at = excluded.updated_at
                """,
                record.userId(),
                record.topicKey(),
                record.muted() ? 1 : 0,
                record.mutedAt() != null ? record.mutedAt().toString() : null,
                record.updatedAt().toString()
        );
    }

    public int deleteFeedbackBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM proactive_reminder_feedback
                WHERE updated_at < ?
                """, cutoff.toString());
    }

    private ReminderNotificationFeedbackView mapFeedbackView(ResultSet rs, int rowNum) throws SQLException {
        return new ReminderNotificationFeedbackView(
                rs.getString("notification_id"),
                rs.getString("topic_key"),
                ReminderFeedbackType.parse(rs.getString("feedback_type")),
                rs.getString("comment"),
                Instant.parse(rs.getString("updated_at")),
                rs.getInt("topic_muted") == 1
        );
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
