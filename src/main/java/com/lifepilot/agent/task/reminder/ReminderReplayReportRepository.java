package com.lifepilot.agent.task.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 主动提醒离线回放报告仓储。
 *
 * <p>负责持久化系统内部的 replay 评估结果。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ReminderReplayReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderReplayReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(ReminderReplayReportRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_replay_reports (
                    id, user_id, since, generated_at, sample_count, historical_push_count,
                    replayed_push_count, suppressed_count, promoted_count, action_shift_count,
                    historical_observed_reward_mean, historical_estimated_push_reward_mean,
                    replayed_estimated_push_reward_mean, action_shift_json, summary_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.userId(),
                record.since().toString(),
                record.generatedAt().toString(),
                record.sampleCount(),
                record.historicalPushCount(),
                record.replayedPushCount(),
                record.suppressedCount(),
                record.promotedCount(),
                record.actionShiftCount(),
                record.historicalObservedRewardMean(),
                record.historicalEstimatedPushRewardMean(),
                record.replayedEstimatedPushRewardMean(),
                record.actionShiftJson(),
                record.summaryJson(),
                record.createdAt().toString()
        );
    }

    public Optional<ReminderReplayReportRecord> findLatestByUserId(String userId) {
        List<ReminderReplayReportRecord> rows = jdbcTemplate.query("""
                SELECT id, user_id, since, generated_at, sample_count, historical_push_count,
                       replayed_push_count, suppressed_count, promoted_count, action_shift_count,
                       historical_observed_reward_mean, historical_estimated_push_reward_mean,
                       replayed_estimated_push_reward_mean, action_shift_json, summary_json, created_at
                FROM proactive_reminder_replay_reports
                WHERE user_id = ?
                ORDER BY generated_at DESC
                LIMIT 1
                """, rowMapper(), userId);
        return rows.stream().findFirst();
    }

    public int deleteBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM proactive_reminder_replay_reports
                WHERE generated_at < ?
                """, cutoff.toString());
    }

    private org.springframework.jdbc.core.RowMapper<ReminderReplayReportRecord> rowMapper() {
        return (rs, _) -> new ReminderReplayReportRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                Instant.parse(rs.getString("since")),
                Instant.parse(rs.getString("generated_at")),
                rs.getInt("sample_count"),
                rs.getInt("historical_push_count"),
                rs.getInt("replayed_push_count"),
                rs.getInt("suppressed_count"),
                rs.getInt("promoted_count"),
                rs.getInt("action_shift_count"),
                rs.getFloat("historical_observed_reward_mean"),
                rs.getFloat("historical_estimated_push_reward_mean"),
                rs.getFloat("replayed_estimated_push_reward_mean"),
                rs.getString("action_shift_json"),
                rs.getString("summary_json"),
                Instant.parse(rs.getString("created_at"))
        );
    }
}
