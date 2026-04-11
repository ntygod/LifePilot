package com.lifepilot.agent.task.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 主动提醒策略版本仓储。
 *
 * <p>负责保存和查询策略版本，为执行链提供稳定的版本引用。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderPolicyVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderPolicyVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(ReminderPolicyVersionRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_policy_versions (
                    id, user_id, version, config_signature, config_json, source,
                    summary_json, activated_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.userId(),
                record.version(),
                record.configSignature(),
                record.configJson(),
                record.source(),
                record.summaryJson(),
                record.activatedAt().toString(),
                record.createdAt().toString()
        );
    }

    public Optional<ReminderPolicyVersionRecord> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, user_id, version, config_signature, config_json, source,
                       summary_json, activated_at, created_at
                FROM proactive_reminder_policy_versions
                WHERE id = ?
                """, rowMapper(), id).stream().findFirst();
    }

    public Optional<ReminderPolicyVersionRecord> findLatestByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, version, config_signature, config_json, source,
                       summary_json, activated_at, created_at
                FROM proactive_reminder_policy_versions
                WHERE user_id = ?
                ORDER BY version DESC
                LIMIT 1
                """, rowMapper(), userId).stream().findFirst();
    }

    public Optional<ReminderPolicyVersionRecord> findByUserIdAndConfigSignature(String userId,
                                                                                String configSignature) {
        return jdbcTemplate.query("""
                SELECT id, user_id, version, config_signature, config_json, source,
                       summary_json, activated_at, created_at
                FROM proactive_reminder_policy_versions
                WHERE user_id = ? AND config_signature = ?
                LIMIT 1
                """, rowMapper(), userId, configSignature).stream().findFirst();
    }

    public int nextVersion(String userId) {
        Integer current = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0)
                FROM proactive_reminder_policy_versions
                WHERE user_id = ?
                """, Integer.class, userId);
        return (current != null ? current : 0) + 1;
    }

    public List<ReminderPolicyVersionRecord> findByUserId(String userId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT id, user_id, version, config_signature, config_json, source,
                       summary_json, activated_at, created_at
                FROM proactive_reminder_policy_versions
                WHERE user_id = ?
                ORDER BY version ASC
                """, rowMapper(), userId));
    }

    private org.springframework.jdbc.core.RowMapper<ReminderPolicyVersionRecord> rowMapper() {
        return (rs, _) -> new ReminderPolicyVersionRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getInt("version"),
                rs.getString("config_signature"),
                rs.getString("config_json"),
                rs.getString("source"),
                rs.getString("summary_json"),
                Instant.parse(rs.getString("activated_at")),
                Instant.parse(rs.getString("created_at"))
        );
    }
}
