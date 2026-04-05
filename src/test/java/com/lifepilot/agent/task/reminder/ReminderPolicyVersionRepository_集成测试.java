package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderPolicyVersionRepository 集成测试。
 *
 * <p>验证策略版本能够正确落库、查询和递增。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderPolicyVersionRepository_集成测试 {

    private ReminderPolicyVersionRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
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
        repository = new ReminderPolicyVersionRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void saveAndFind_能够按签名和最新版本读取() {
        Instant now = Instant.parse("2026-03-28T10:00:00Z");
        ReminderPolicyVersionRecord version1 = new ReminderPolicyVersionRecord(
                UUID.randomUUID().toString(),
                "default",
                1,
                "sig-1",
                "{\"dailyMaxReminders\":2}",
                "base",
                "{\"feedback\":{\"totalFeedbackCount\":0}}",
                now,
                now
        );
        ReminderPolicyVersionRecord version2 = new ReminderPolicyVersionRecord(
                UUID.randomUUID().toString(),
                "default",
                2,
                "sig-2",
                "{\"dailyMaxReminders\":1}",
                "feedback+replay",
                "{\"feedback\":{\"totalFeedbackCount\":6}}",
                now.plusSeconds(60),
                now.plusSeconds(60)
        );

        repository.save(version1);
        repository.save(version2);

        assertThat(repository.findById(version1.id())).contains(version1);
        assertThat(repository.findByUserIdAndConfigSignature("default", "sig-2")).contains(version2);
        assertThat(repository.findLatestByUserId("default")).contains(version2);
        assertThat(repository.nextVersion("default")).isEqualTo(3);
    }
}
