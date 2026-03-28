package com.lifepilot.agent.task.reminder;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动提醒主题别名仓储。
 *
 * <p>负责保存 alias -> canonical 的稳定映射，支撑 topic 生命周期归并。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ReminderTopicAliasRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderTopicAliasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(ReminderTopicAliasRecord record) {
        jdbcTemplate.update("""
                INSERT INTO proactive_reminder_topic_aliases (
                    user_id, alias_topic_key, canonical_topic_key, topic_family, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, alias_topic_key) DO UPDATE SET
                    canonical_topic_key = excluded.canonical_topic_key,
                    topic_family = excluded.topic_family,
                    updated_at = excluded.updated_at
                """,
                record.userId(),
                record.aliasTopicKey(),
                record.canonicalTopicKey(),
                record.topicFamily(),
                record.createdAt().toString(),
                record.updatedAt().toString()
        );
    }

    public Map<String, String> findAliasMapByUserId(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT alias_topic_key, canonical_topic_key
                FROM proactive_reminder_topic_aliases
                WHERE user_id = ?
                """, userId);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(row.get("alias_topic_key").toString(), row.get("canonical_topic_key").toString());
        }
        return Map.copyOf(result);
    }

    public int deleteStaleAliasesBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM proactive_reminder_topic_aliases
                WHERE updated_at < ?
                """, cutoff.toString());
    }
}
