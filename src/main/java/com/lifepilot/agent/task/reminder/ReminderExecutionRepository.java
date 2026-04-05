package com.lifepilot.agent.task.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 主动提醒执行仓储。
 *
 * <p>负责保存 reminder run、decision 和 evidence，支撑回放与阈值调优。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderExecutionRepository {

    private static final Logger log = LoggerFactory.getLogger(ReminderExecutionRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ReminderRunRecord> RUN_ROW_MAPPER = (rs, _) -> new ReminderRunRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            Instant.parse(rs.getString("started_at")),
            parseInstant(rs.getString("finished_at")),
            rs.getInt("topics_collected"),
            rs.getInt("decisions_evaluated"),
            rs.getInt("reminders_sent"),
            rs.getString("policy_version_id"),
            getNullableInteger(rs.getObject("policy_version")),
            rs.getString("context_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    private static final RowMapper<ReminderDecisionRecord> DECISION_ROW_MAPPER = (rs, _) -> new ReminderDecisionRecord(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("topic_key"),
            rs.getString("title"),
            rs.getString("signal_id"),
            rs.getString("candidate_type"),
            rs.getString("action"),
            rs.getString("decision_reason"),
            rs.getString("rationale"),
            rs.getFloat("final_score"),
            rs.getFloat("evidence_score"),
            rs.getFloat("timing_score"),
            rs.getFloat("urgency_score"),
            rs.getFloat("user_fit_score"),
            rs.getFloat("actionability_score"),
            rs.getFloat("duplicate_penalty"),
            rs.getFloat("fatigue_penalty"),
            parseInstant(rs.getString("suggested_at")),
            parseInstant(rs.getString("next_evaluation_at")),
            rs.getInt("notified") == 1,
            rs.getString("notification_id"),
            parseInstant(rs.getString("topic_last_reminded_at")),
            rs.getInt("topic_reminders_sent_today"),
            rs.getInt("topic_read_count_30d"),
            rs.getInt("topic_acted_count_30d"),
            rs.getInt("topic_dismissed_count_30d"),
            rs.getInt("topic_snoozed_count_30d"),
            rs.getInt("topic_not_relevant_count_30d"),
            rs.getInt("topic_muted") == 1,
            rs.getString("policy_version_id"),
            getNullableInteger(rs.getObject("policy_version")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    private static final RowMapper<ReminderEvidenceRecord> EVIDENCE_ROW_MAPPER = (rs, _) -> new ReminderEvidenceRecord(
            rs.getString("id"),
            rs.getString("decision_id"),
            rs.getString("signal_id"),
            rs.getString("signal_kind"),
            rs.getFloat("confidence_score"),
            rs.getFloat("importance_score"),
            rs.getInt("evidence_count"),
            Instant.parse(rs.getString("observed_at")),
            parseInstant(rs.getString("relevant_at")),
            getNullableInteger(rs.getObject("preparation_lead_minutes")),
            getNullableInteger(rs.getObject("preferred_window_start_hour")),
            getNullableInteger(rs.getObject("preferred_window_end_hour")),
            rs.getFloat("anomaly_score"),
            rs.getInt("actionable") == 1,
            rs.getInt("resolved") == 1,
            rs.getString("summary"),
            Instant.parse(rs.getString("created_at"))
    );
    private static final RowMapper<ReminderDeferredWakeup> DEFERRED_WAKEUP_ROW_MAPPER = (rs, _) -> new ReminderDeferredWakeup(
            rs.getString("decision_id"),
            rs.getString("run_id"),
            rs.getString("user_id"),
            rs.getString("topic_key"),
            rs.getString("title"),
            rs.getString("signal_id"),
            rs.getString("candidate_type"),
            Instant.parse(rs.getString("next_evaluation_at"))
    );

    public ReminderExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveRun(ReminderRunRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_runs (
                    id, user_id, started_at, finished_at, topics_collected, decisions_evaluated,
                    reminders_sent, policy_version_id, policy_version, context_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.userId(),
                record.startedAt().toString(),
                toDbTime(record.finishedAt()),
                record.topicsCollected(),
                record.decisionsEvaluated(),
                record.remindersSent(),
                record.policyVersionId(),
                record.policyVersion(),
                record.contextJson(),
                record.createdAt().toString(),
                record.updatedAt().toString()
        );
    }

    public void updateRun(ReminderRunRecord record) {
        jdbcTemplate.update("""
                UPDATE proactive_reminder_runs
                SET finished_at = ?, topics_collected = ?, decisions_evaluated = ?, reminders_sent = ?,
                    policy_version_id = ?, policy_version = ?, context_json = ?, updated_at = ?
                WHERE id = ?
                """,
                toDbTime(record.finishedAt()),
                record.topicsCollected(),
                record.decisionsEvaluated(),
                record.remindersSent(),
                record.policyVersionId(),
                record.policyVersion(),
                record.contextJson(),
                record.updatedAt().toString(),
                record.id()
        );
    }

    public Optional<ReminderRunRecord> findRunById(String id) {
        List<ReminderRunRecord> rows = jdbcTemplate.query(
                "SELECT * FROM proactive_reminder_runs WHERE id = ?",
                RUN_ROW_MAPPER,
                id
        );
        return rows.stream().findFirst();
    }

    public void saveDecision(ReminderDecisionRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_decisions (
                    id, run_id, topic_key, title, signal_id, candidate_type, action,
                    decision_reason, rationale, final_score, evidence_score, timing_score,
                    urgency_score, user_fit_score, actionability_score, duplicate_penalty,
                    fatigue_penalty, suggested_at, next_evaluation_at, notified, notification_id,
                    topic_last_reminded_at, topic_reminders_sent_today, topic_read_count_30d,
                    topic_acted_count_30d, topic_dismissed_count_30d, topic_snoozed_count_30d,
                    topic_not_relevant_count_30d, topic_muted, policy_version_id, policy_version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.runId(),
                record.topicKey(),
                record.title(),
                record.signalId(),
                record.candidateType(),
                record.action(),
                record.decisionReason(),
                record.rationale(),
                record.finalScore(),
                record.evidenceScore(),
                record.timingScore(),
                record.urgencyScore(),
                record.userFitScore(),
                record.actionabilityScore(),
                record.duplicatePenalty(),
                record.fatiguePenalty(),
                toDbTime(record.suggestedAt()),
                toDbTime(record.nextEvaluationAt()),
                toDbBoolean(record.notified()),
                record.notificationId(),
                toDbTime(record.topicLastRemindedAt()),
                record.topicRemindersSentToday(),
                record.topicReadCount30d(),
                record.topicActedCount30d(),
                record.topicDismissedCount30d(),
                record.topicSnoozedCount30d(),
                record.topicNotRelevantCount30d(),
                toDbBoolean(record.topicMuted()),
                record.policyVersionId(),
                record.policyVersion(),
                record.createdAt().toString(),
                record.updatedAt().toString()
        );
    }

    public List<ReminderDecisionRecord> findDecisionsByRunId(String runId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT * FROM proactive_reminder_decisions
                WHERE run_id = ?
                ORDER BY created_at ASC
                """, DECISION_ROW_MAPPER, runId));
    }

    public List<ReminderDecisionRecord> findRecentDecisionsByTopicKey(String topicKey, int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT * FROM proactive_reminder_decisions
                WHERE topic_key = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, DECISION_ROW_MAPPER, topicKey, limit));
    }

    public List<ReminderOutcomeInferenceCandidate> findPendingOutcomeInferenceCandidates(String userId,
                                                                                         Instant since,
                                                                                         int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT d.id AS decision_id,
                       d.notification_id,
                       r.user_id,
                       d.topic_key,
                       d.title,
                       d.candidate_type,
                       d.created_at AS decided_at
                FROM proactive_reminder_decisions d
                JOIN proactive_reminder_runs r
                  ON r.id = d.run_id
                LEFT JOIN (
                    SELECT notification_id,
                           MAX(CASE WHEN feedback_type = 'ACTED' THEN 1 ELSE 0 END) AS acted_flag
                    FROM proactive_reminder_feedback
                    GROUP BY notification_id
                ) f
                  ON f.notification_id = d.notification_id
                LEFT JOIN (
                    SELECT decision_id,
                           MAX(CASE WHEN outcome_type = 'ACTED' THEN 1 ELSE 0 END) AS inferred_acted_flag
                    FROM proactive_reminder_inferred_outcomes
                    GROUP BY decision_id
                ) o
                  ON o.decision_id = d.id
                WHERE r.user_id = ?
                  AND d.notified = 1
                  AND d.action IN ('SOFT_PUSH', 'NORMAL_PUSH')
                  AND d.created_at >= ?
                  AND COALESCE(f.acted_flag, 0) = 0
                  AND COALESCE(o.inferred_acted_flag, 0) = 0
                ORDER BY d.created_at DESC
                LIMIT ?
                """, (rs, _) -> new ReminderOutcomeInferenceCandidate(
                rs.getString("decision_id"),
                rs.getString("notification_id"),
                rs.getString("user_id"),
                rs.getString("topic_key"),
                rs.getString("title"),
                rs.getString("candidate_type"),
                Instant.parse(rs.getString("decided_at"))
        ), userId, since.toString(), limit));
    }

    public List<ReminderReplaySample> findReplaySamplesByUserIdSince(String userId,
                                                                     Instant since,
                                                                     int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT d.id AS decision_id,
                       d.created_at AS decided_at,
                       d.topic_key,
                       d.title,
                       d.signal_id,
                       d.candidate_type,
                       d.action AS historical_action,
                       COALESCE(pt.base_action, d.action) AS base_action,
                       d.next_evaluation_at,
                       d.decision_reason,
                       d.final_score,
                       d.evidence_score,
                       d.timing_score,
                       d.urgency_score,
                       d.user_fit_score,
                       d.actionability_score,
                       d.duplicate_penalty,
                       d.fatigue_penalty,
                       d.topic_reminders_sent_today,
                       d.topic_read_count_30d,
                       d.topic_acted_count_30d,
                       d.topic_dismissed_count_30d,
                       d.topic_snoozed_count_30d,
                       d.topic_not_relevant_count_30d,
                       COALESCE(f.acted_flag, 0) AS acted_flag,
                       COALESCE(o.inferred_acted_flag, 0) AS inferred_acted_flag,
                       COALESCE(o.inferred_acted_attribution, 0) AS inferred_acted_attribution,
                       COALESCE(f.snoozed_flag, 0) AS snoozed_flag,
                       COALESCE(f.dismissed_flag, 0) AS dismissed_flag,
                       COALESCE(f.not_relevant_flag, 0) AS not_relevant_flag,
                       CASE
                           WHEN COALESCE(f.acted_flag, 0) = 0
                            AND COALESCE(f.snoozed_flag, 0) = 0
                            AND COALESCE(f.dismissed_flag, 0) = 0
                            AND COALESCE(f.not_relevant_flag, 0) = 0
                            AND COALESCE(o.inferred_acted_flag, 0) = 0
                            AND n.read_status = 'READ'
                           THEN 1
                           ELSE 0
                       END AS read_only_flag
                FROM proactive_reminder_decisions d
                JOIN proactive_reminder_runs r
                  ON r.id = d.run_id
                LEFT JOIN proactive_reminder_policy_traces pt
                  ON pt.decision_id = d.id
                LEFT JOIN (
                    SELECT notification_id,
                           MAX(CASE WHEN feedback_type = 'ACTED' THEN 1 ELSE 0 END) AS acted_flag,
                           MAX(CASE WHEN feedback_type = 'SNOOZED' THEN 1 ELSE 0 END) AS snoozed_flag,
                           MAX(CASE WHEN feedback_type = 'DISMISSED' THEN 1 ELSE 0 END) AS dismissed_flag,
                           MAX(CASE WHEN feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END) AS not_relevant_flag
                    FROM proactive_reminder_feedback
                    GROUP BY notification_id
                ) f
                  ON f.notification_id = d.notification_id
                LEFT JOIN (
                    SELECT decision_id,
                           MAX(CASE WHEN outcome_type = 'ACTED' THEN 1 ELSE 0 END) AS inferred_acted_flag,
                           MAX(CASE WHEN outcome_type = 'ACTED' THEN attribution_score ELSE 0 END) AS inferred_acted_attribution
                    FROM proactive_reminder_inferred_outcomes
                    GROUP BY decision_id
                ) o
                  ON o.decision_id = d.id
                LEFT JOIN notification_history n
                  ON n.id = d.notification_id
                WHERE r.user_id = ?
                  AND d.notified = 1
                  AND d.action IN ('SOFT_PUSH', 'NORMAL_PUSH')
                  AND d.created_at >= ?
                ORDER BY d.created_at ASC
                LIMIT ?
                """, (rs, _) -> new ReminderReplaySample(
                rs.getString("decision_id"),
                Instant.parse(rs.getString("decided_at")),
                rs.getString("topic_key"),
                rs.getString("title"),
                rs.getString("signal_id"),
                ReminderCandidateType.valueOf(rs.getString("candidate_type")),
                ReminderAction.valueOf(rs.getString("historical_action")),
                ReminderAction.valueOf(rs.getString("base_action")),
                parseInstant(rs.getString("next_evaluation_at")),
                rs.getString("decision_reason"),
                rs.getFloat("final_score"),
                rs.getFloat("evidence_score"),
                rs.getFloat("timing_score"),
                rs.getFloat("urgency_score"),
                rs.getFloat("user_fit_score"),
                rs.getFloat("actionability_score"),
                rs.getFloat("duplicate_penalty"),
                rs.getFloat("fatigue_penalty"),
                rs.getInt("topic_reminders_sent_today"),
                rs.getInt("topic_read_count_30d"),
                rs.getInt("topic_acted_count_30d"),
                rs.getInt("topic_dismissed_count_30d"),
                rs.getInt("topic_snoozed_count_30d"),
                rs.getInt("topic_not_relevant_count_30d"),
                rs.getInt("acted_flag") == 1 || rs.getInt("inferred_acted_flag") == 1,
                rs.getInt("acted_flag") == 1
                        ? 1.0f
                        : ReminderRewardModel.implicitActedReward(rs.getFloat("inferred_acted_attribution")),
                rs.getInt("snoozed_flag") == 1,
                rs.getInt("dismissed_flag") == 1,
                rs.getInt("not_relevant_flag") == 1,
                rs.getInt("read_only_flag") == 1
        ), userId, since.toString(), limit));
    }

    public List<String> findRecentActiveUserIdsSince(Instant since, int limit) {
        return List.copyOf(jdbcTemplate.queryForList("""
                SELECT r.user_id
                FROM proactive_reminder_runs r
                WHERE r.started_at >= ?
                GROUP BY r.user_id
                ORDER BY MAX(r.started_at) DESC
                LIMIT ?
                """, String.class, since.toString(), Math.max(1, limit)));
    }

    public int deleteRunsBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM proactive_reminder_runs
                WHERE updated_at < ?
                """, cutoff.toString());
    }

    public List<ReminderActionPerformanceStats> summarizeActionPerformanceByUserIdSince(String userId, Instant since) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.candidate_type,
                       d.action,
                       COUNT(*) AS sent_count,
                       COALESCE(SUM(CASE
                           WHEN COALESCE(f.acted_flag, 0) = 1 OR COALESCE(o.inferred_acted_flag, 0) = 1 THEN 1
                           ELSE 0
                       END), 0) AS acted_count,
                       COALESCE(SUM(CASE WHEN COALESCE(f.snoozed_flag, 0) = 1 THEN 1 ELSE 0 END), 0) AS snoozed_count,
                       COALESCE(SUM(CASE WHEN COALESCE(f.dismissed_flag, 0) = 1 THEN 1 ELSE 0 END), 0) AS dismissed_count,
                       COALESCE(SUM(CASE WHEN COALESCE(f.not_relevant_flag, 0) = 1 THEN 1 ELSE 0 END), 0) AS not_relevant_count,
                       COALESCE(SUM(CASE
                           WHEN COALESCE(f.acted_flag, 0) = 0
                            AND COALESCE(f.snoozed_flag, 0) = 0
                            AND COALESCE(f.dismissed_flag, 0) = 0
                            AND COALESCE(f.not_relevant_flag, 0) = 0
                            AND COALESCE(o.inferred_acted_flag, 0) = 0
                            AND n.read_status = 'READ' THEN 1
                           ELSE 0
                       END), 0) AS read_only_count
                FROM proactive_reminder_decisions d
                JOIN proactive_reminder_runs r
                  ON r.id = d.run_id
                LEFT JOIN (
                    SELECT notification_id,
                           MAX(CASE WHEN feedback_type = 'ACTED' THEN 1 ELSE 0 END) AS acted_flag,
                           MAX(CASE WHEN feedback_type = 'SNOOZED' THEN 1 ELSE 0 END) AS snoozed_flag,
                           MAX(CASE WHEN feedback_type = 'DISMISSED' THEN 1 ELSE 0 END) AS dismissed_flag,
                           MAX(CASE WHEN feedback_type = 'NOT_RELEVANT' THEN 1 ELSE 0 END) AS not_relevant_flag
                    FROM proactive_reminder_feedback
                    GROUP BY notification_id
                ) f
                  ON f.notification_id = d.notification_id
                LEFT JOIN (
                    SELECT decision_id,
                           MAX(CASE WHEN outcome_type = 'ACTED' THEN 1 ELSE 0 END) AS inferred_acted_flag
                    FROM proactive_reminder_inferred_outcomes
                    GROUP BY decision_id
                ) o
                  ON o.decision_id = d.id
                LEFT JOIN notification_history n
                  ON n.id = d.notification_id
                WHERE r.user_id = ?
                  AND d.notified = 1
                  AND d.action IN ('SOFT_PUSH', 'NORMAL_PUSH')
                  AND d.created_at >= ?
                GROUP BY d.candidate_type, d.action
                """, userId, since.toString());
        List<ReminderActionPerformanceStats> result = new ArrayList<>();
        for (java.util.Map<String, Object> row : rows) {
            result.add(new ReminderActionPerformanceStats(
                    row.get("candidate_type").toString(),
                    ReminderAction.valueOf(row.get("action").toString()),
                    getNullableInteger(row.get("sent_count")),
                    getNullableInteger(row.get("acted_count")),
                    getNullableInteger(row.get("snoozed_count")),
                    getNullableInteger(row.get("dismissed_count")),
                    getNullableInteger(row.get("not_relevant_count")),
                    getNullableInteger(row.get("read_only_count"))
            ));
        }
        return List.copyOf(result);
    }

    public List<ReminderActionTrainingExample> findActionTrainingExamplesByUserIdSince(String userId,
                                                                                       Instant since,
                                                                                       int limit) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.candidate_type,
                       d.action,
                       d.final_score,
                       d.evidence_score,
                       d.timing_score,
                       d.urgency_score,
                       d.user_fit_score,
                       d.actionability_score,
                       d.duplicate_penalty,
                       d.fatigue_penalty,
                       d.topic_reminders_sent_today,
                       d.topic_read_count_30d,
                       d.topic_acted_count_30d,
                       d.topic_dismissed_count_30d,
                       d.topic_snoozed_count_30d,
                       d.topic_not_relevant_count_30d,
                       COALESCE(f.feedback_type, '') AS feedback_type,
                       n.read_status,
                       COALESCE(o.inferred_acted_attribution, 0) AS inferred_acted_attribution
                FROM proactive_reminder_decisions d
                JOIN proactive_reminder_runs r
                  ON r.id = d.run_id
                LEFT JOIN (
                    SELECT notification_id,
                           MAX(feedback_type) AS feedback_type
                    FROM proactive_reminder_feedback
                    GROUP BY notification_id
                ) f
                  ON f.notification_id = d.notification_id
                LEFT JOIN (
                    SELECT decision_id,
                           MAX(CASE WHEN outcome_type = 'ACTED' THEN attribution_score ELSE 0 END) AS inferred_acted_attribution
                    FROM proactive_reminder_inferred_outcomes
                    GROUP BY decision_id
                ) o
                  ON o.decision_id = d.id
                LEFT JOIN notification_history n
                  ON n.id = d.notification_id
                WHERE r.user_id = ?
                  AND d.notified = 1
                  AND d.action IN ('SOFT_PUSH', 'NORMAL_PUSH')
                  AND d.created_at >= ?
                ORDER BY d.created_at DESC
                LIMIT ?
                """, userId, since.toString(), limit);
        List<ReminderActionTrainingExample> result = new ArrayList<>();
        for (java.util.Map<String, Object> row : rows) {
            result.add(new ReminderActionTrainingExample(
                    row.get("candidate_type").toString(),
                    ReminderAction.valueOf(row.get("action").toString()),
                    asFloat(row.get("final_score")),
                    asFloat(row.get("evidence_score")),
                    asFloat(row.get("timing_score")),
                    asFloat(row.get("urgency_score")),
                    asFloat(row.get("user_fit_score")),
                    asFloat(row.get("actionability_score")),
                    asFloat(row.get("duplicate_penalty")),
                    asFloat(row.get("fatigue_penalty")),
                    asInt(row.get("topic_reminders_sent_today")),
                    asInt(row.get("topic_read_count_30d")),
                    asInt(row.get("topic_acted_count_30d")),
                    asInt(row.get("topic_dismissed_count_30d")),
                    asInt(row.get("topic_snoozed_count_30d")),
                    asInt(row.get("topic_not_relevant_count_30d")),
                    rewardFor((String) row.get("feedback_type"),
                            (String) row.get("read_status"),
                            asFloat(row.get("inferred_acted_attribution")))
            ));
        }
        return List.copyOf(result);
    }

    public List<ReminderDeferredWakeup> findDueDeferredWakeups(Instant now, int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT d.id AS decision_id,
                       d.run_id,
                       r.user_id,
                       d.topic_key,
                       d.title,
                       d.signal_id,
                       d.candidate_type,
                       d.next_evaluation_at
                FROM proactive_reminder_decisions d
                JOIN proactive_reminder_runs r
                  ON r.id = d.run_id
                JOIN (
                    SELECT r2.user_id AS user_id,
                           d2.topic_key AS topic_key,
                           MAX(d2.created_at) AS latest_created_at
                    FROM proactive_reminder_decisions d2
                    JOIN proactive_reminder_runs r2
                      ON r2.id = d2.run_id
                    GROUP BY r2.user_id, d2.topic_key
                ) latest
                  ON latest.user_id = r.user_id
                 AND latest.topic_key = d.topic_key
                 AND latest.latest_created_at = d.created_at
                WHERE d.action = 'DEFER_TO_WINDOW'
                  AND d.next_evaluation_at IS NOT NULL
                  AND d.next_evaluation_at <= ?
                ORDER BY d.next_evaluation_at ASC
                LIMIT ?
                """, DEFERRED_WAKEUP_ROW_MAPPER, now.toString(), limit));
    }

    public void saveEvidenceBatch(List<ReminderEvidenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO proactive_reminder_evidence (
                            id, decision_id, signal_id, signal_kind, confidence_score, importance_score,
                            evidence_count, observed_at, relevant_at, preparation_lead_minutes,
                            preferred_window_start_hour, preferred_window_end_hour, anomaly_score,
                            actionable, resolved, summary, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                records,
                records.size(),
                (ps, record) -> {
                    ps.setString(1, record.id());
                    ps.setString(2, record.decisionId());
                    ps.setString(3, record.signalId());
                    ps.setString(4, record.signalKind());
                    ps.setFloat(5, record.confidenceScore());
                    ps.setFloat(6, record.importanceScore());
                    ps.setInt(7, record.evidenceCount());
                    ps.setString(8, record.observedAt().toString());
                    ps.setString(9, toDbTime(record.relevantAt()));
                    setNullableInteger(ps, 10, record.preparationLeadMinutes());
                    setNullableInteger(ps, 11, record.preferredWindowStartHour());
                    setNullableInteger(ps, 12, record.preferredWindowEndHour());
                    ps.setFloat(13, record.anomalyScore());
                    ps.setInt(14, toDbBoolean(record.actionable()));
                    ps.setInt(15, toDbBoolean(record.resolved()));
                    ps.setString(16, record.summary());
                    ps.setString(17, record.createdAt().toString());
                });
    }

    public List<ReminderEvidenceRecord> findEvidenceByDecisionId(String decisionId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT * FROM proactive_reminder_evidence
                WHERE decision_id = ?
                ORDER BY created_at ASC, id ASC
                """, EVIDENCE_ROW_MAPPER, decisionId));
    }

    public void savePolicyTrace(ReminderPolicyTraceRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_policy_traces (
                    decision_id, base_action, opportunity_action, final_action,
                    opportunity_adjusted, action_adjusted, training_example_count,
                    action_feedback_sample_count, trace_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.decisionId(),
                record.baseAction(),
                record.opportunityAction(),
                record.finalAction(),
                toDbBoolean(record.opportunityAdjusted()),
                toDbBoolean(record.actionAdjusted()),
                record.trainingExampleCount(),
                record.actionFeedbackSampleCount(),
                record.traceJson(),
                record.createdAt().toString()
        );
    }

    public Optional<ReminderPolicyTraceRecord> findPolicyTraceByDecisionId(String decisionId) {
        List<ReminderPolicyTraceRecord> rows = jdbcTemplate.query("""
                SELECT * FROM proactive_reminder_policy_traces
                WHERE decision_id = ?
                """, (rs, _) -> new ReminderPolicyTraceRecord(
                rs.getString("decision_id"),
                rs.getString("base_action"),
                rs.getString("opportunity_action"),
                rs.getString("final_action"),
                rs.getInt("opportunity_adjusted") == 1,
                rs.getInt("action_adjusted") == 1,
                rs.getInt("training_example_count"),
                rs.getInt("action_feedback_sample_count"),
                rs.getString("trace_json"),
                Instant.parse(rs.getString("created_at"))
        ), decisionId);
        return rows.stream().findFirst();
    }

    private static String toDbTime(Instant value) {
        return value != null ? value.toString() : null;
    }

    private static int toDbBoolean(boolean value) {
        return value ? 1 : 0;
    }

    private static Integer getNullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static void setNullableInteger(java.sql.PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
            return;
        }
        ps.setInt(index, value);
    }

    private static Instant parseInstant(String value) {
        return value != null && !value.isBlank() ? Instant.parse(value) : null;
    }

    private static int asInt(Object value) {
        Integer result = getNullableInteger(value);
        return result != null ? result : 0;
    }

    private static float asFloat(Object value) {
        if (value == null) {
            return 0.0f;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return Float.parseFloat(value.toString());
    }

    /**
     * 保存反事实合成的训练样本到决策表，以便 Bandit 后续读取。
     *
     * <p>每条样本作为一条虚拟决策记录写入，action 标记为实际推荐动作，
     * run_id 使用特殊前缀 {@code counterfactual:} 以区分真实执行记录。</p>
     */
    public void saveCounterfactualExamples(String userId, List<ReminderActionTrainingExample> examples) {
        Instant now = Instant.now();
        String runId = "counterfactual:" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_runs (
                    id, user_id, started_at, finished_at,
                    topics_collected, decisions_evaluated, reminders_sent,
                    context_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId, userId, now.toString(), now.toString(),
                examples.size(), examples.size(), 0,
                "{\"source\":\"counterfactual_warmup\"}", now.toString(), now.toString()
        );
        for (ReminderActionTrainingExample example : examples) {
            jdbcTemplate.update("""
                    INSERT INTO proactive_reminder_decisions (
                        id, run_id, topic_key, title, signal_id, candidate_type,
                        action, decision_reason, rationale,
                        final_score, evidence_score, timing_score, urgency_score,
                        user_fit_score, actionability_score, duplicate_penalty, fatigue_penalty,
                        topic_reminders_sent_today, topic_read_count_30d, topic_acted_count_30d,
                        topic_dismissed_count_30d, topic_snoozed_count_30d, topic_not_relevant_count_30d,
                        topic_muted, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), runId,
                    "counterfactual:" + example.candidateType(),
                    "反事实合成样本",
                    "counterfactual:" + UUID.randomUUID(),
                    example.candidateType(),
                    example.action().name(),
                    "反事实样本预热",
                    "从历史记忆合成的训练样本",
                    example.finalScore(), example.evidenceScore(),
                    example.timingScore(), example.urgencyScore(),
                    example.userFitScore(), example.actionabilityScore(),
                    example.duplicatePenalty(), example.fatiguePenalty(),
                    example.topicRemindersSentToday(), example.topicReadCount30d(),
                    example.topicActedCount30d(), example.topicDismissedCount30d(),
                    example.topicSnoozedCount30d(), example.topicNotRelevantCount30d(),
                    0,
                    now.toString(), now.toString()
            );
        }
    }

    private static float rewardFor(String feedbackType, String readStatus, float inferredActedAttribution) {
        return ReminderRewardModel.rewardFor(feedbackType, readStatus, inferredActedAttribution);
    }
}
