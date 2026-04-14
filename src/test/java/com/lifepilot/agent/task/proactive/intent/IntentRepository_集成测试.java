package com.lifepilot.agent.task.proactive.intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentRepository 集成测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class IntentRepository_集成测试 {

    private IntentRepository repo;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_intent_memory (
                    id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL,
                    intent_type TEXT NOT NULL, goal TEXT NOT NULL,
                    trigger_condition TEXT, source_session_id TEXT,
                    status TEXT NOT NULL DEFAULT 'ACTIVE', check_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL, expires_at TEXT, triggered_at TEXT,
                    fulfilled_at TEXT, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX idx_intent_user_status ON proactive_intent_memory (user_id, status, created_at DESC)");
        repo = new IntentRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.destroy();
    }

    @Test
    void 保存并按状态查询() {
        var now = Instant.now();
        var record = new IntentRecord(UUID.randomUUID().toString(), "u1", IntentType.GOAL,
                "买耳机", "价格低于300", "sess-1", IntentStatus.ACTIVE, 0,
                now, now.plusSeconds(90 * 86400), null, null, now);
        repo.save(record);

        var active = repo.findActiveByUserId("u1");
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().goal()).isEqualTo("买耳机");
    }

    @Test
    void 按ID查询() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.GOAL, "目标",
                null, null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.goal()).isEqualTo("目标");
    }

    @Test
    void 更新状态为TRIGGERED() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.CONDITIONAL, "等降价",
                "价格<300", null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        repo.updateStatus(id, IntentStatus.TRIGGERED, now);

        var found = repo.findById(id);
        assertThat(found).isNotNull();
        assertThat(found.status()).isEqualTo(IntentStatus.TRIGGERED);
        assertThat(found.triggeredAt()).isNotNull();
    }

    @Test
    void 更新状态为FULFILLED() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.GOAL, "目标",
                null, null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        repo.updateStatus(id, IntentStatus.FULFILLED, now);

        var found = repo.findById(id);
        assertThat(found.status()).isEqualTo(IntentStatus.FULFILLED);
        assertThat(found.fulfilledAt()).isNotNull();
    }

    @Test
    void 递增检查计数() {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        repo.save(new IntentRecord(id, "u1", IntentType.GOAL, "目标",
                null, null, IntentStatus.ACTIVE, 0, now, null, null, null, now));

        repo.incrementCheckCount(id);
        repo.incrementCheckCount(id);

        var found = repo.findById(id);
        assertThat(found.checkCount()).isEqualTo(2);
    }

    @Test
    void 过期意图查询() {
        var now = Instant.now();
        repo.save(new IntentRecord(UUID.randomUUID().toString(), "u1", IntentType.GOAL, "已过期",
                null, null, IntentStatus.ACTIVE, 5,
                now.minusSeconds(100 * 86400), now.minusSeconds(86400),
                null, null, now.minusSeconds(100 * 86400)));
        // 未过期的不应出现
        repo.save(new IntentRecord(UUID.randomUUID().toString(), "u1", IntentType.GOAL, "未过期",
                null, null, IntentStatus.ACTIVE, 0,
                now, now.plusSeconds(86400), null, null, now));

        var expired = repo.findExpired(now);
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().goal()).isEqualTo("已过期");
    }
}
