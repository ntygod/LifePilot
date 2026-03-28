package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderReplayReportRepository 集成测试。
 *
 * <p>验证 replay 报告能够正确落库并读取最新记录。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
class ReminderReplayReportRepository_集成测试 {

    private ReminderReplayReportRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_reminder_replay_reports (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    since TEXT NOT NULL,
                    generated_at TEXT NOT NULL,
                    sample_count INTEGER NOT NULL DEFAULT 0,
                    historical_push_count INTEGER NOT NULL DEFAULT 0,
                    replayed_push_count INTEGER NOT NULL DEFAULT 0,
                    suppressed_count INTEGER NOT NULL DEFAULT 0,
                    promoted_count INTEGER NOT NULL DEFAULT 0,
                    action_shift_count INTEGER NOT NULL DEFAULT 0,
                    historical_observed_reward_mean REAL NOT NULL DEFAULT 0,
                    historical_estimated_push_reward_mean REAL NOT NULL DEFAULT 0,
                    replayed_estimated_push_reward_mean REAL NOT NULL DEFAULT 0,
                    action_shift_json TEXT,
                    summary_json TEXT,
                    created_at TEXT NOT NULL
                )""");
        repository = new ReminderReplayReportRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void saveAndFindLatestByUserId_能够回读最新报告() {
        Instant now = Instant.parse("2026-03-29T01:00:00Z");
        repository.save(new ReminderReplayReportRecord(
                "report-1",
                "default",
                now.minusSeconds(86400),
                now.minusSeconds(300),
                18,
                12,
                11,
                3,
                2,
                5,
                0.64f,
                0.62f,
                0.67f,
                "{\"SOFT_PUSH->NORMAL_PUSH\":2}",
                "{\"expectedDelta\":0.05}",
                now.minusSeconds(300)
        ));
        repository.save(new ReminderReplayReportRecord(
                "report-2",
                "default",
                now.minusSeconds(86400),
                now,
                22,
                14,
                13,
                4,
                3,
                6,
                0.66f,
                0.63f,
                0.71f,
                "{\"NORMAL_PUSH->SKIP\":1}",
                "{\"expectedDelta\":0.08}",
                now
        ));

        ReminderReplayReportRecord latest = repository.findLatestByUserId("default").orElseThrow();

        assertThat(latest.id()).isEqualTo("report-2");
        assertThat(latest.sampleCount()).isEqualTo(22);
        assertThat(latest.summaryJson()).contains("expectedDelta");
    }

    @Test
    void deleteBefore_仅清理超期报告() {
        Instant now = Instant.parse("2026-03-29T01:00:00Z");
        repository.save(new ReminderReplayReportRecord(
                "report-old",
                "default",
                now.minusSeconds(86400),
                now.minusSeconds(7200),
                12,
                8,
                7,
                1,
                1,
                2,
                0.60f,
                0.58f,
                0.61f,
                "{}",
                "{}",
                now.minusSeconds(7200)
        ));
        repository.save(new ReminderReplayReportRecord(
                "report-new",
                "default",
                now.minusSeconds(86400),
                now,
                16,
                10,
                9,
                2,
                1,
                3,
                0.63f,
                0.61f,
                0.66f,
                "{}",
                "{}",
                now
        ));

        int deleted = repository.deleteBefore(now.minusSeconds(3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findLatestByUserId("default")).isPresent()
                .get()
                .extracting(ReminderReplayReportRecord::id)
                .isEqualTo("report-new");
    }
}
