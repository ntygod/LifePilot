package com.lifepilot.agent.task.proactive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueuedActionRepository 集成测试。
 *
 * <p>验证排队动作能正确落库、查询、标记已展示和清理。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
class QueuedActionRepository_集成测试 {

    private QueuedActionRepository repo;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_queued_actions (
                    id          TEXT    NOT NULL PRIMARY KEY,
                    user_id     TEXT    NOT NULL,
                    behavior    TEXT    NOT NULL,
                    topic_key   TEXT    NOT NULL,
                    title       TEXT    NOT NULL,
                    content     TEXT    NOT NULL,
                    score       REAL    NOT NULL,
                    metadata    TEXT,
                    shown       INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT    NOT NULL,
                    shown_at    TEXT
                )
                """);
        jdbc.execute("""
                CREATE INDEX idx_proactive_queued_actions_user_shown_score
                    ON proactive_queued_actions (user_id, shown, score DESC, created_at DESC)
                """);
        repo = new QueuedActionRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void 保存并查询未展示的排队动作() {
        var record = new QueuedActionRecord(
                UUID.randomUUID().toString(), "u1", "reminder",
                "topic-1", "标题", "内容", 0.45f, null,
                false, Instant.now(), null);

        repo.save(record);

        var pending = repo.findPendingByUserId("u1", 10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().topicKey()).isEqualTo("topic-1");
        assertThat(pending.getFirst().shown()).isFalse();
    }

    @Test
    void 标记为已展示后不再返回() {
        var id = UUID.randomUUID().toString();
        var record = new QueuedActionRecord(
                id, "u1", "reminder", "topic-2", "标题", "内容",
                0.4f, null, false, Instant.now(), null);

        repo.save(record);
        repo.markShown(id);

        var pending = repo.findPendingByUserId("u1", 10);
        assertThat(pending).isEmpty();
    }

    @Test
    void 按分数降序返回() {
        repo.save(new QueuedActionRecord(UUID.randomUUID().toString(), "u1", "reminder",
                "low", "低分", "内容", 0.3f, null, false, Instant.now(), null));
        repo.save(new QueuedActionRecord(UUID.randomUUID().toString(), "u1", "reminder",
                "high", "高分", "内容", 0.8f, null, false, Instant.now(), null));

        var pending = repo.findPendingByUserId("u1", 10);
        assertThat(pending).hasSize(2);
        assertThat(pending.getFirst().topicKey()).isEqualTo("high");
    }

    @Test
    void 清理过期排队动作() {
        var old = new QueuedActionRecord(
                UUID.randomUUID().toString(), "u1", "reminder",
                "topic-old", "旧标题", "旧内容", 0.35f, null,
                false, Instant.now().minusSeconds(8 * 86400), null);
        repo.save(old);

        int deleted = repo.deleteOlderThan(Instant.now().minusSeconds(7 * 86400));

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findPendingByUserId("u1", 10)).isEmpty();
    }

    @Test
    void 不同用户数据隔离() {
        repo.save(new QueuedActionRecord(UUID.randomUUID().toString(), "u1", "reminder",
                "topic-u1", "用户1", "内容", 0.5f, null, false, Instant.now(), null));
        repo.save(new QueuedActionRecord(UUID.randomUUID().toString(), "u2", "reminder",
                "topic-u2", "用户2", "内容", 0.5f, null, false, Instant.now(), null));

        assertThat(repo.findPendingByUserId("u1", 10)).hasSize(1);
        assertThat(repo.findPendingByUserId("u2", 10)).hasSize(1);
    }
}
