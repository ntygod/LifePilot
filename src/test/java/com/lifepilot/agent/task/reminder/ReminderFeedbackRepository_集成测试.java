package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderFeedbackRepository 集成测试。
 *
 * <p>验证主动提醒反馈、主题静默和统计汇总能够正确落库与读取。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderFeedbackRepository_集成测试 {

    private ReminderFeedbackRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");
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
                    insight_entity_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (notification_id) REFERENCES notification_history(id) ON DELETE CASCADE
                )""");
        jdbc.execute("""
                CREATE TABLE proactive_reminder_topic_preferences (
                    user_id TEXT NOT NULL,
                    topic_key TEXT NOT NULL,
                    muted INTEGER NOT NULL DEFAULT 0,
                    muted_at TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, topic_key)
                )""");
        repository = new ReminderFeedbackRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 保存反馈并汇总统计_能够读取反馈视图和主题静默() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        String notificationId = "notification-1";
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                notificationId,
                "default",
                "proactive_reminder",
                "{}",
                "WEB",
                "READ",
                "SENT",
                "{\"topicKey\":\"conversation:web:conv-1\"}",
                now.toString(),
                now.toString(),
                now.toString()
        );

        repository.saveFeedback(new ReminderFeedbackRecord(
                UUID.randomUUID().toString(),
                notificationId,
                "default",
                "conversation:web:conv-1",
                ReminderFeedbackType.ACTED,
                "已经处理",
                now,
                now
        ));
        repository.upsertTopicPreference(new ReminderTopicPreferenceRecord(
                "default",
                "conversation:web:conv-1",
                true,
                now,
                now
        ));

        var feedbackView = repository.findFeedbackViewByNotificationId(notificationId).orElseThrow();
        Map<String, ReminderTopicFeedbackStats> stats = repository.summarizeTopicStatsByUserIdSince(
                "default",
                now.minusSeconds(3600)
        );
        ReminderUserFeedbackSummary summary = repository.summarizeUserFeedbackByUserIdSince(
                "default",
                now.minusSeconds(3600)
        );

        assertThat(feedbackView.feedbackType()).isEqualTo(ReminderFeedbackType.ACTED);
        assertThat(feedbackView.topicMuted()).isTrue();
        assertThat(stats).containsKey("conversation:web:conv-1");
        assertThat(stats.get("conversation:web:conv-1").actedCount30d()).isEqualTo(1);
        assertThat(stats.get("conversation:web:conv-1").muted()).isTrue();
        assertThat(summary.actedCount()).isEqualTo(1);
        assertThat(summary.mutedTopicCount()).isEqualTo(1);
    }

    @Test
    void deleteFeedbackBefore_仅清理超期反馈() {
        Instant now = Instant.parse("2026-03-28T12:00:00Z");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO notification_history (
                    id, user_id, type_id, content_json, channel, read_status, status,
                    metadata_json, sent_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "notification-old", "default", "proactive_reminder", "{}", "WEB", "UNREAD", "SENT",
                "{}", now.minusSeconds(86400).toString(), now.minusSeconds(86400).toString(), now.minusSeconds(86400).toString(),
                "notification-new", "default", "proactive_reminder", "{}", "WEB", "UNREAD", "SENT",
                "{}", now.toString(), now.toString(), now.toString()
        );
        repository.saveFeedback(new ReminderFeedbackRecord(
                UUID.randomUUID().toString(),
                "notification-old",
                "default",
                "topic:old",
                ReminderFeedbackType.DISMISSED,
                null,
                now.minusSeconds(86400),
                now.minusSeconds(86400)
        ));
        repository.saveFeedback(new ReminderFeedbackRecord(
                UUID.randomUUID().toString(),
                "notification-new",
                "default",
                "topic:new",
                ReminderFeedbackType.ACTED,
                null,
                now,
                now
        ));

        int deleted = repository.deleteFeedbackBefore(now.minusSeconds(3600));

        Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM proactive_reminder_feedback", Integer.class);
        assertThat(deleted).isEqualTo(1);
        assertThat(remaining).isEqualTo(1);
    }
}
