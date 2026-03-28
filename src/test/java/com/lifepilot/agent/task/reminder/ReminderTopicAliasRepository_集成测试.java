package com.lifepilot.agent.task.reminder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReminderTopicAliasRepository 集成测试。
 *
 * <p>验证主题别名映射能够正确持久化、读取和清理。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
class ReminderTopicAliasRepository_集成测试 {

    private ReminderTopicAliasRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_reminder_topic_aliases (
                    user_id TEXT NOT NULL,
                    alias_topic_key TEXT NOT NULL,
                    canonical_topic_key TEXT NOT NULL,
                    topic_family TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, alias_topic_key)
                )""");
        repository = new ReminderTopicAliasRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void upsertAndFindAliasMapByUserId_能够回读最新映射() {
        Instant now = Instant.parse("2026-03-29T02:00:00Z");
        repository.upsert(new ReminderTopicAliasRecord(
                "default",
                "conversation:web:conv-1",
                "topic:task:abc123",
                "task",
                now,
                now
        ));
        repository.upsert(new ReminderTopicAliasRecord(
                "default",
                "experience:exp-1",
                "topic:task:abc123",
                "task",
                now,
                now.plusSeconds(60)
        ));

        Map<String, String> aliasMap = repository.findAliasMapByUserId("default");

        assertThat(aliasMap).containsEntry("conversation:web:conv-1", "topic:task:abc123");
        assertThat(aliasMap).containsEntry("experience:exp-1", "topic:task:abc123");
    }

    @Test
    void deleteStaleAliasesBefore_仅删除过期别名() {
        Instant now = Instant.parse("2026-03-29T02:00:00Z");
        repository.upsert(new ReminderTopicAliasRecord(
                "default",
                "conversation:web:old",
                "topic:task:old",
                "task",
                now.minusSeconds(86400),
                now.minusSeconds(86400)
        ));
        repository.upsert(new ReminderTopicAliasRecord(
                "default",
                "conversation:web:new",
                "topic:task:new",
                "task",
                now,
                now
        ));

        int deleted = repository.deleteStaleAliasesBefore(now.minusSeconds(3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findAliasMapByUserId("default"))
                .containsOnly(Map.entry("conversation:web:new", "topic:task:new"));
    }
}
