package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderExecutionRepository 集成测试。
 *
 * <p>验证主动提醒执行链的 run / decision / evidence 能正确落库和回读。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderExecutionRepository_集成测试 {

    private ReminderExecutionRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_runs (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    topics_collected INTEGER NOT NULL DEFAULT 0,
                    decisions_evaluated INTEGER NOT NULL DEFAULT 0,
                    reminders_sent INTEGER NOT NULL DEFAULT 0,
                    policy_version_id TEXT,
                    policy_version INTEGER,
                    context_json TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_decisions (
                    id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL,
                    topic_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    signal_id TEXT NOT NULL,
                    candidate_type TEXT NOT NULL,
                    action TEXT NOT NULL,
                    decision_reason TEXT,
                    rationale TEXT,
                    final_score REAL NOT NULL,
                    evidence_score REAL NOT NULL,
                    timing_score REAL NOT NULL,
                    urgency_score REAL NOT NULL,
                    user_fit_score REAL NOT NULL,
                    actionability_score REAL NOT NULL,
                    duplicate_penalty REAL NOT NULL,
                    fatigue_penalty REAL NOT NULL,
                    suggested_at TEXT,
                    next_evaluation_at TEXT,
                    notified INTEGER NOT NULL DEFAULT 0,
                    notification_id TEXT,
                    topic_last_reminded_at TEXT,
                    topic_reminders_sent_today INTEGER NOT NULL DEFAULT 0,
                    topic_read_count_30d INTEGER NOT NULL DEFAULT 0,
                    topic_acted_count_30d INTEGER NOT NULL DEFAULT 0,
                    topic_dismissed_count_30d INTEGER NOT NULL DEFAULT 0,
                    topic_snoozed_count_30d INTEGER NOT NULL DEFAULT 0,
                    topic_not_relevant_count_30d INTEGER NOT NULL DEFAULT 0,
                    topic_muted INTEGER NOT NULL DEFAULT 0,
                    policy_version_id TEXT,
                    policy_version INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (run_id) REFERENCES proactive_reminder_runs(id) ON DELETE CASCADE
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_evidence (
                    id TEXT PRIMARY KEY,
                    decision_id TEXT NOT NULL,
                    signal_id TEXT NOT NULL,
                    signal_kind TEXT NOT NULL,
                    confidence_score REAL NOT NULL,
                    importance_score REAL NOT NULL,
                    evidence_count INTEGER NOT NULL,
                    observed_at TEXT NOT NULL,
                    relevant_at TEXT,
                    preparation_lead_minutes INTEGER,
                    preferred_window_start_hour INTEGER,
                    preferred_window_end_hour INTEGER,
                    anomaly_score REAL NOT NULL DEFAULT 0,
                    actionable INTEGER NOT NULL DEFAULT 0,
                    resolved INTEGER NOT NULL DEFAULT 0,
                    summary TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (decision_id) REFERENCES proactive_reminder_decisions(id) ON DELETE CASCADE
                )""");
        jdbc.execute("""
                CREATE TABLE notification_history (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    type_id TEXT,
                    content_json TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    read_status TEXT NOT NULL,
                    status TEXT NOT NULL,
                    metadata_json TEXT,
                    sent_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_feedback (
                    id TEXT PRIMARY KEY,
                    notification_id TEXT NOT NULL UNIQUE,
                    user_id TEXT NOT NULL,
                    topic_key TEXT NOT NULL,
                    feedback_type TEXT NOT NULL,
                    comment TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_policy_traces (
                    decision_id TEXT PRIMARY KEY,
                    base_action TEXT NOT NULL,
                    opportunity_action TEXT NOT NULL,
                    final_action TEXT NOT NULL,
                    opportunity_adjusted INTEGER NOT NULL DEFAULT 0,
                    action_adjusted INTEGER NOT NULL DEFAULT 0,
                    training_example_count INTEGER NOT NULL DEFAULT 0,
                    action_feedback_sample_count INTEGER NOT NULL DEFAULT 0,
                    trace_json TEXT,
                    created_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_policy_versions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    config_signature TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    source TEXT NOT NULL,
                    summary_json TEXT,
                    activated_at TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_inferred_outcomes (
                    id TEXT PRIMARY KEY,
                    decision_id TEXT NOT NULL,
                    notification_id TEXT,
                    user_id TEXT NOT NULL,
                    topic_key TEXT NOT NULL,
                    outcome_type TEXT NOT NULL,
                    evidence_source TEXT NOT NULL,
                    confidence_score REAL NOT NULL DEFAULT 0,
                    attribution_score REAL NOT NULL DEFAULT 1.0,
                    evidence_json TEXT,
                    inferred_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
        repository = new ReminderExecutionRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void saveRunAndDecisionAndEvidence_能够完整回读() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now,
                null,
                0,
                0,
                0,
                "{\"zoneId\":\"Asia/Shanghai\"}",
                now,
                now
        ));
        repository.updateRun(new ReminderRunRecord(
                runId,
                "default",
                now,
                now.plusSeconds(30),
                3,
                2,
                1,
                "{\"zoneId\":\"Asia/Shanghai\"}",
                now,
                now.plusSeconds(30)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                decisionId,
                runId,
                "conversation:web:conv-1",
                "周计划整理",
                "conversation:web:conv-1",
                "HABIT_WINDOW",
                "NORMAL_PUSH",
                "命中高优先级提醒条件",
                "已接近用户的稳定习惯窗口",
                0.91f,
                0.80f,
                0.92f,
                0.72f,
                0.60f,
                1.00f,
                0.00f,
                0.05f,
                now.plusSeconds(60),
                null,
                true,
                "notification-1",
                now.minusSeconds(7200),
                0,
                1,
                0,
                0,
                0,
                0,
                false,
                now,
                now
        ));
        repository.saveEvidenceBatch(List.of(
                new ReminderEvidenceRecord(
                        UUID.randomUUID().toString(),
                        decisionId,
                        "signal-1",
                        "HABIT",
                        0.88f,
                        0.76f,
                        3,
                        now.minusSeconds(3600),
                        now.plusSeconds(60),
                        null,
                        21,
                        23,
                        0.0f,
                        true,
                        false,
                        "每周日晚上 21:00 整理下周计划",
                        now
                )
        ));

        var run = repository.findRunById(runId).orElseThrow();
        var decisions = repository.findDecisionsByRunId(runId);
        var evidences = repository.findEvidenceByDecisionId(decisionId);

        assertThat(run.decisionsEvaluated()).isEqualTo(2);
        assertThat(run.remindersSent()).isEqualTo(1);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().notificationId()).isEqualTo("notification-1");
        assertThat(repository.findRecentDecisionsByTopicKey("conversation:web:conv-1", 5)).hasSize(1);
        assertThat(evidences).hasSize(1);
        assertThat(evidences.getFirst().preferredWindowStartHour()).isEqualTo(21);
    }

    @Test
    void deleteRunsBefore_能够清理超期运行记录() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        repository.saveRun(new ReminderRunRecord(
                "run-old",
                "default",
                now.minusSeconds(86400),
                now.minusSeconds(86400),
                1,
                0,
                0,
                null,
                null,
                "{}",
                now.minusSeconds(86400),
                now.minusSeconds(86400)
        ));
        repository.saveRun(new ReminderRunRecord(
                "run-new",
                "default",
                now,
                now,
                1,
                0,
                0,
                null,
                null,
                "{}",
                now,
                now
        ));

        int deleted = repository.deleteRunsBefore(now.minusSeconds(3600));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM proactive_reminder_runs", Integer.class);
        assertThat(deleted).isEqualTo(1);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void saveRunAndDecision_策略版本字段能够回读() {
        Instant now = Instant.parse("2026-03-28T14:00:00Z");
        String runId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now,
                now.plusSeconds(10),
                1,
                1,
                0,
                "policy-v2",
                2,
                "{\"policyVersion\":2}",
                now,
                now.plusSeconds(10)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                decisionId,
                runId,
                "entity:expense",
                "报销处理",
                "signal-expense",
                "COMMITMENT_GAP",
                "SKIP",
                "当前时机不适合提醒",
                "先记录策略版本",
                0.41f,
                0.50f,
                0.36f,
                0.40f,
                0.52f,
                0.40f,
                0.08f,
                0.12f,
                now,
                now.plusSeconds(1800),
                false,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "policy-v2",
                2,
                now,
                now
        ));

        var run = repository.findRunById(runId).orElseThrow();
        var decision = repository.findDecisionsByRunId(runId).getFirst();

        assertThat(run.policyVersionId()).isEqualTo("policy-v2");
        assertThat(run.policyVersion()).isEqualTo(2);
        assertThat(decision.policyVersionId()).isEqualTo("policy-v2");
        assertThat(decision.policyVersion()).isEqualTo(2);
    }

    @Test
    void findRecentActiveUserIdsSince_能够按最近活跃时间返回用户() {
        Instant now = Instant.parse("2026-03-29T08:00:00Z");
        repository.saveRun(new ReminderRunRecord(
                "run-user-1-old",
                "user-1",
                now.minusSeconds(7200),
                null,
                1,
                1,
                0,
                null,
                null,
                "{}",
                now.minusSeconds(7200),
                now.minusSeconds(7200)
        ));
        repository.saveRun(new ReminderRunRecord(
                "run-user-2",
                "user-2",
                now.minusSeconds(1800),
                null,
                1,
                1,
                0,
                null,
                null,
                "{}",
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));
        repository.saveRun(new ReminderRunRecord(
                "run-user-1-new",
                "user-1",
                now.minusSeconds(600),
                null,
                1,
                1,
                0,
                null,
                null,
                "{}",
                now.minusSeconds(600),
                now.minusSeconds(600)
        ));

        List<String> userIds = repository.findRecentActiveUserIdsSince(now.minusSeconds(86400), 10);

        assertThat(userIds).containsExactly("user-1", "user-2");
    }

    @Test
    void savePolicyTrace_能够回读策略追踪() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String decisionId = UUID.randomUUID().toString();

        repository.savePolicyTrace(new ReminderPolicyTraceRecord(
                decisionId,
                "SKIP",
                "SOFT_PUSH",
                "NORMAL_PUSH",
                true,
                true,
                32,
                14,
                "{\"summary\":\"base=SKIP, opportunity=SOFT_PUSH, final=NORMAL_PUSH\"}",
                now
        ));

        ReminderPolicyTraceRecord trace = repository.findPolicyTraceByDecisionId(decisionId).orElseThrow();

        assertThat(trace.baseAction()).isEqualTo("SKIP");
        assertThat(trace.opportunityAction()).isEqualTo("SOFT_PUSH");
        assertThat(trace.finalAction()).isEqualTo("NORMAL_PUSH");
        assertThat(trace.trainingExampleCount()).isEqualTo(32);
        assertThat(trace.actionFeedbackSampleCount()).isEqualTo(14);
        assertThat(trace.traceJson()).contains("final=NORMAL_PUSH");
    }

    @Test
    void findDueDeferredWakeups_仅返回每个主题最近一条到期延后决策() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId1 = UUID.randomUUID().toString();
        String runId2 = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(runId1, "default", now.minusSeconds(3600), null,
                0, 0, 0, null, now.minusSeconds(3600), now.minusSeconds(3600)));
        repository.saveRun(new ReminderRunRecord(runId2, "default", now.minusSeconds(1800), null,
                0, 0, 0, null, now.minusSeconds(1800), now.minusSeconds(1800)));

        repository.saveDecision(new ReminderDecisionRecord(
                UUID.randomUUID().toString(),
                runId1,
                "topic-1",
                "周计划整理",
                "sig-1",
                "HABIT_WINDOW",
                "DEFER_TO_WINDOW",
                "等待更合适时间",
                "原始延后",
                0.70f,
                0.60f,
                0.70f,
                0.40f,
                0.50f,
                0.80f,
                0.00f,
                0.00f,
                now.minusSeconds(1800),
                now.minusSeconds(600),
                false,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(3600),
                now.minusSeconds(3600)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                UUID.randomUUID().toString(),
                runId2,
                "topic-1",
                "周计划整理",
                "sig-1",
                "HABIT_WINDOW",
                "DEFER_TO_WINDOW",
                "继续等待窗口",
                "最新延后",
                0.75f,
                0.65f,
                0.72f,
                0.42f,
                0.52f,
                0.82f,
                0.00f,
                0.00f,
                now.minusSeconds(900),
                now.minusSeconds(120),
                false,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                UUID.randomUUID().toString(),
                runId2,
                "topic-2",
                "报销处理",
                "sig-2",
                "COMMITMENT_GAP",
                "DEFER_TO_WINDOW",
                "稍后提醒",
                "等待工作时段",
                0.62f,
                0.58f,
                0.61f,
                0.40f,
                0.48f,
                0.70f,
                0.00f,
                0.00f,
                now.minusSeconds(300),
                now.plusSeconds(600),
                false,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(1700),
                now.minusSeconds(1700)
        ));

        List<ReminderDeferredWakeup> wakeups = repository.findDueDeferredWakeups(now, 10);

        assertThat(wakeups).hasSize(1);
        assertThat(wakeups.getFirst().topicKey()).isEqualTo("topic-1");
        assertThat(wakeups.getFirst().runId()).isEqualTo(runId2);
        assertThat(wakeups.getFirst().nextEvaluationAt()).isEqualTo(now.minusSeconds(120));
    }

    @Test
    void findPendingOutcomeInferenceCandidates_排除已显式处理和已推断决策() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId = UUID.randomUUID().toString();
        String pendingDecisionId = UUID.randomUUID().toString();
        String actedDecisionId = UUID.randomUUID().toString();
        String inferredDecisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now.minusSeconds(1800),
                null,
                0,
                0,
                0,
                null,
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository.saveDecision(new ReminderDecisionRecord(
                pendingDecisionId,
                runId,
                "topic-pending",
                "待推断主题",
                "sig-pending",
                "COMMITMENT_GAP",
                "SOFT_PUSH",
                "等待后续结果",
                "等待用户完成",
                0.66f,
                0.62f,
                0.64f,
                0.42f,
                0.60f,
                0.84f,
                0.01f,
                0.02f,
                now.minusSeconds(600),
                null,
                true,
                "notification-pending",
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(600),
                now.minusSeconds(600)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                actedDecisionId,
                runId,
                "topic-acted",
                "显式处理主题",
                "sig-acted",
                "DUE_SOON",
                "NORMAL_PUSH",
                "等待后续结果",
                "已显式标记处理",
                0.92f,
                0.88f,
                0.90f,
                0.95f,
                0.58f,
                0.96f,
                0.00f,
                0.02f,
                now.minusSeconds(500),
                null,
                true,
                "notification-acted",
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(500),
                now.minusSeconds(500)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                inferredDecisionId,
                runId,
                "topic-inferred",
                "已推断主题",
                "sig-inferred",
                "HABIT_WINDOW",
                "SOFT_PUSH",
                "等待后续结果",
                "已有隐式完成",
                0.73f,
                0.68f,
                0.71f,
                0.45f,
                0.64f,
                0.82f,
                0.01f,
                0.02f,
                now.minusSeconds(400),
                null,
                true,
                "notification-inferred",
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(400),
                now.minusSeconds(400)
        ));

        jdbc.update("""
                INSERT INTO proactive_reminder_feedback (
                    id, notification_id, user_id, topic_key, feedback_type, comment, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), "notification-acted", "default", "topic-acted",
                "ACTED", null, now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO proactive_reminder_inferred_outcomes (
                    id, decision_id, notification_id, user_id, topic_key, outcome_type,
                    evidence_source, confidence_score, attribution_score, evidence_json,
                    inferred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), inferredDecisionId, "notification-inferred", "default",
                "topic-inferred", "ACTED", "WORKSPACE_STATE", 0.95f, 0.94f, "{}",
                now.toString(), now.toString(), now.toString());

        List<ReminderOutcomeInferenceCandidate> candidates = repository.findPendingOutcomeInferenceCandidates(
                "default", now.minusSeconds(3600), 10
        );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().decisionId()).isEqualTo(pendingDecisionId);
        assertThat(candidates.getFirst().topicKey()).isEqualTo("topic-pending");
    }

    @Test
    void findReplaySamplesByUserIdSince_能够返回基础动作与结果标签() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId = UUID.randomUUID().toString();
        String promotedDecisionId = UUID.randomUUID().toString();
        String inferredDecisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now.minusSeconds(1800),
                null,
                0,
                0,
                0,
                null,
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "notification-promoted", "default", "proactive_reminder", "{}", "WEB", "READ", "SENT",
                "{}", now.toString(), now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "notification-inferred", "default", "proactive_reminder", "{}", "WEB", "UNREAD", "SENT",
                "{}", now.toString(), now.toString(), now.toString());

        repository.saveDecision(new ReminderDecisionRecord(
                promotedDecisionId,
                runId,
                "topic-promoted",
                "整理报销",
                "sig-promoted",
                "COMMITMENT_GAP",
                "SOFT_PUSH",
                ReminderSkipReason.LOW_SCORE.label() + "；结合近期类似场景反馈，补发轻提醒",
                "最近反复提到",
                0.53f,
                0.62f,
                0.72f,
                0.42f,
                0.78f,
                0.86f,
                0.02f,
                0.03f,
                now.minusSeconds(600),
                null,
                true,
                "notification-promoted",
                null,
                0,
                2,
                1,
                0,
                0,
                0,
                false,
                now.minusSeconds(600),
                now.minusSeconds(600)
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                inferredDecisionId,
                runId,
                "topic-inferred",
                "缴纳水费",
                "sig-inferred",
                "DUE_SOON",
                "NORMAL_PUSH",
                "命中高优先级提醒条件",
                "今晚截止",
                0.90f,
                0.85f,
                0.90f,
                0.95f,
                0.58f,
                0.95f,
                0.00f,
                0.03f,
                now.minusSeconds(300),
                null,
                true,
                "notification-inferred",
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now.minusSeconds(300),
                now.minusSeconds(300)
        ));
        repository.savePolicyTrace(new ReminderPolicyTraceRecord(
                promotedDecisionId,
                "SKIP",
                "SOFT_PUSH",
                "SOFT_PUSH",
                true,
                false,
                6,
                4,
                "{}",
                now.minusSeconds(600)
        ));
        jdbc.update("""
                INSERT INTO proactive_reminder_inferred_outcomes (
                    id, decision_id, notification_id, user_id, topic_key, outcome_type,
                    evidence_source, confidence_score, attribution_score, evidence_json,
                    inferred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), inferredDecisionId, "notification-inferred", "default",
                "topic-inferred", "ACTED", "SEMANTIC_STATE", 0.88f, 0.76f, "{}",
                now.toString(), now.toString(), now.toString());

        List<ReminderReplaySample> samples = repository.findReplaySamplesByUserIdSince(
                "default", now.minusSeconds(3600), 10
        );

        assertThat(samples).hasSize(2);
        ReminderReplaySample promoted = samples.getFirst();
        ReminderReplaySample inferred = samples.get(1);
        assertThat(promoted.baseAction()).isEqualTo(ReminderAction.SKIP);
        assertThat(promoted.historicalAction()).isEqualTo(ReminderAction.SOFT_PUSH);
        assertThat(promoted.readOnly()).isTrue();
        assertThat(inferred.acted()).isTrue();
        assertThat(inferred.historicalReward()).isEqualTo(ReminderRewardModel.implicitActedReward(0.76f));
        assertThat(inferred.readOnly()).isFalse();
    }

    @Test
    void summarizeActionPerformanceByUserIdSince_能够聚合显式与隐式结果() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId = UUID.randomUUID().toString();
        String softNotificationId = "notification-soft";
        String normalNotificationId = "notification-normal";
        String softDecisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now.minusSeconds(1800),
                null,
                0,
                0,
                0,
                null,
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                softNotificationId, "default", "proactive_reminder", "{}", "WEB", "READ", "SENT",
                "{}", now.toString(), now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                normalNotificationId, "default", "proactive_reminder", "{}", "WEB", "UNREAD", "SENT",
                "{}", now.toString(), now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO proactive_reminder_feedback (
                    id, notification_id, user_id, topic_key, feedback_type, comment, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), normalNotificationId, "default", "topic-bill", "DISMISSED",
                null, now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO proactive_reminder_inferred_outcomes (
                    id, decision_id, notification_id, user_id, topic_key, outcome_type,
                    evidence_source, confidence_score, attribution_score, evidence_json,
                    inferred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), softDecisionId, softNotificationId, "default",
                "topic-plan", "ACTED", "CONVERSATION_MESSAGE", 0.80f, 0.71f, "{}",
                now.toString(), now.toString(), now.toString());

        repository.saveDecision(new ReminderDecisionRecord(
                softDecisionId,
                runId,
                "topic-plan",
                "周计划整理",
                "sig-soft",
                "HABIT_WINDOW",
                "SOFT_PUSH",
                "适合发送轻提醒",
                "历史中等强度更好",
                0.70f,
                0.68f,
                0.66f,
                0.42f,
                0.60f,
                0.90f,
                0.00f,
                0.02f,
                now,
                null,
                true,
                softNotificationId,
                null,
                0,
                1,
                0,
                0,
                0,
                0,
                false,
                now,
                now
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                UUID.randomUUID().toString(),
                runId,
                "topic-bill",
                "缴水费",
                "sig-normal",
                "DUE_SOON",
                "NORMAL_PUSH",
                "命中高优先级提醒条件",
                "截止临近",
                0.90f,
                0.85f,
                0.90f,
                0.95f,
                0.58f,
                0.95f,
                0.00f,
                0.03f,
                now,
                null,
                true,
                normalNotificationId,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                now,
                now
        ));

        List<ReminderActionPerformanceStats> stats = repository.summarizeActionPerformanceByUserIdSince(
                "default", now.minusSeconds(3600)
        );

        assertThat(stats).hasSize(2);
        ReminderActionPerformanceStats softStats = stats.stream()
                .filter(stat -> stat.action() == ReminderAction.SOFT_PUSH)
                .findFirst()
                .orElseThrow();
        ReminderActionPerformanceStats normalStats = stats.stream()
                .filter(stat -> stat.action() == ReminderAction.NORMAL_PUSH)
                .findFirst()
                .orElseThrow();

        assertThat(softStats.actedCount()).isEqualTo(1);
        assertThat(softStats.readOnlyCount()).isZero();
        assertThat(normalStats.dismissedCount()).isEqualTo(1);
    }

    @Test
    void findActionTrainingExamplesByUserIdSince_能够回放训练样本与隐式奖励() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String runId = UUID.randomUUID().toString();
        String readNotificationId = "notification-read";
        String inferredNotificationId = "notification-inferred";
        String inferredDecisionId = UUID.randomUUID().toString();

        repository.saveRun(new ReminderRunRecord(
                runId,
                "default",
                now.minusSeconds(1800),
                null,
                0,
                0,
                0,
                null,
                now.minusSeconds(1800),
                now.minusSeconds(1800)
        ));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                readNotificationId, "default", "proactive_reminder", "{}", "WEB", "READ", "SENT",
                "{}", now.toString(), now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inferredNotificationId, "default", "proactive_reminder", "{}", "WEB", "READ", "SENT",
                "{}", now.toString(), now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO proactive_reminder_inferred_outcomes (
                    id, decision_id, notification_id, user_id, topic_key, outcome_type,
                    evidence_source, confidence_score, attribution_score, evidence_json,
                    inferred_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), inferredDecisionId, inferredNotificationId, "default",
                "topic-bill", "ACTED", "SEMANTIC_STATE", 0.88f, 0.76f, "{}",
                now.toString(), now.toString(), now.toString());

        repository.saveDecision(new ReminderDecisionRecord(
                UUID.randomUUID().toString(),
                runId,
                "topic-plan",
                "周计划整理",
                "sig-read",
                "HABIT_WINDOW",
                "SOFT_PUSH",
                "适合发送轻提醒",
                "轻触达更合适",
                0.72f,
                0.70f,
                0.74f,
                0.40f,
                0.68f,
                0.88f,
                0.02f,
                0.04f,
                now,
                null,
                true,
                readNotificationId,
                null,
                0,
                2,
                1,
                0,
                1,
                0,
                false,
                now,
                now
        ));
        repository.saveDecision(new ReminderDecisionRecord(
                inferredDecisionId,
                runId,
                "topic-bill",
                "缴水费",
                "sig-acted",
                "DUE_SOON",
                "NORMAL_PUSH",
                "命中高优先级提醒条件",
                "今晚截止",
                0.91f,
                0.88f,
                0.90f,
                0.94f,
                0.60f,
                0.96f,
                0.00f,
                0.02f,
                now,
                null,
                true,
                inferredNotificationId,
                null,
                0,
                0,
                1,
                0,
                0,
                0,
                false,
                now,
                now
        ));

        List<ReminderActionTrainingExample> examples = repository.findActionTrainingExamplesByUserIdSince(
                "default", now.minusSeconds(3600), 10
        );

        assertThat(examples).hasSize(2);
        ReminderActionTrainingExample inferredActedExample = examples.stream()
                .filter(example -> example.action() == ReminderAction.NORMAL_PUSH)
                .findFirst()
                .orElseThrow();
        ReminderActionTrainingExample readExample = examples.stream()
                .filter(example -> example.action() == ReminderAction.SOFT_PUSH)
                .findFirst()
                .orElseThrow();
        assertThat(inferredActedExample.reward()).isEqualTo(ReminderRewardModel.implicitActedReward(0.76f));
        assertThat(readExample.reward()).isEqualTo(0.58f);
    }
}
