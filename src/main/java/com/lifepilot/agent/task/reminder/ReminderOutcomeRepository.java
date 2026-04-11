package com.lifepilot.agent.task.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提醒隐式结果仓储。
 *
 * <p>保存系统自动推断出的提醒后续结果，并提供 topic 级统计。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderOutcomeRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderOutcomeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveOutcome(ReminderInferredOutcomeRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_inferred_outcomes (
                    id, decision_id, notification_id, user_id, topic_key, outcome_type,
                    evidence_source, confidence_score, attribution_score, evidence_json,
                    inferred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(decision_id, outcome_type, evidence_source) DO UPDATE SET
                    notification_id = excluded.notification_id,
                    user_id = excluded.user_id,
                    topic_key = excluded.topic_key,
                    confidence_score = excluded.confidence_score,
                    attribution_score = excluded.attribution_score,
                    evidence_json = excluded.evidence_json,
                    inferred_at = excluded.inferred_at,
                    updated_at = excluded.updated_at
                """,
                record.id(),
                record.decisionId(),
                record.notificationId(),
                record.userId(),
                record.topicKey(),
                record.outcomeType().name(),
                record.evidenceSource().name(),
                record.confidenceScore(),
                record.attributionScore(),
                record.evidenceJson(),
                record.inferredAt().toString(),
                record.createdAt().toString(),
                record.updatedAt().toString()
        );
    }

    public Map<String, Integer> summarizeActedCountByTopicSince(String userId, Instant since) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT topic_key, COUNT(*) AS acted_count
                FROM proactive_reminder_inferred_outcomes
                WHERE user_id = ?
                  AND outcome_type = 'ACTED'
                  AND inferred_at >= ?
                GROUP BY topic_key
                """, userId, since.toString());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(row.get("topic_key").toString(), asInt(row.get("acted_count")));
        }
        return result;
    }

    public Optional<ReminderInferredOutcomeRecord> findByDecisionId(String decisionId) {
        List<ReminderInferredOutcomeRecord> rows = jdbcTemplate.query("""
                SELECT id, decision_id, notification_id, user_id, topic_key, outcome_type,
                       evidence_source, confidence_score, attribution_score, evidence_json,
                       inferred_at, created_at, updated_at
                FROM proactive_reminder_inferred_outcomes
                WHERE decision_id = ?
                ORDER BY inferred_at DESC
                LIMIT 1
                """, (rs, _) -> new ReminderInferredOutcomeRecord(
                rs.getString("id"),
                rs.getString("decision_id"),
                rs.getString("notification_id"),
                rs.getString("user_id"),
                rs.getString("topic_key"),
                ReminderOutcomeType.valueOf(rs.getString("outcome_type")),
                ReminderOutcomeEvidenceSource.valueOf(rs.getString("evidence_source")),
                rs.getFloat("confidence_score"),
                rs.getFloat("attribution_score"),
                rs.getString("evidence_json"),
                Instant.parse(rs.getString("inferred_at")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        ), decisionId);
        return rows.stream().findFirst();
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
