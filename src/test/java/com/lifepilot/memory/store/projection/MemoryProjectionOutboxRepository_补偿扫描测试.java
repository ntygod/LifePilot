package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryProjectionOutboxRepository 补偿扫描测试。
 *
 * @author zsg
 * @since 2026-06-18
 */
class MemoryProjectionOutboxRepository_补偿扫描测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryProjectionOutboxRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE memory_projection_outbox (
                    id              TEXT PRIMARY KEY,
                    aggregate_type  TEXT NOT NULL,
                    aggregate_id    TEXT NOT NULL,
                    projection_type TEXT NOT NULL,
                    operation       TEXT NOT NULL,
                    payload_json    TEXT NOT NULL,
                    status          TEXT NOT NULL DEFAULT 'PENDING',
                    attempt_count   INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TEXT,
                    last_error      TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL,
                    processed_at    TEXT
                )
                """);
        repository = new MemoryProjectionOutboxRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 到期任务包含pending_failed到期和超时processing() {
        Instant now = Instant.now();
        insert("pending-1", "PENDING", null, now, now);
        insert("failed-due", "FAILED", now.minusSeconds(1), now.plusSeconds(1), now.plusSeconds(1));
        insert("failed-later", "FAILED", now.plusSeconds(3600), now.plusSeconds(2), now.plusSeconds(2));
        insert("processing-stale", "PROCESSING", null, now.plusSeconds(3), now.minusSeconds(600));
        insert("processing-fresh", "PROCESSING", null, now.plusSeconds(4), now);
        insert("processed-1", "PROCESSED", null, now.plusSeconds(5), now);

        var ids = repository.findDueTaskIds(10, now.minusSeconds(300));

        assertThat(ids).containsExactly("pending-1", "failed-due", "processing-stale");
    }

    @Test
    void 普通认领拒绝processing_补偿认领允许processing() {
        Instant now = Instant.now();
        insert("processing-1", "PROCESSING", null, now, now.minusSeconds(600));

        assertThat(repository.markProcessing("processing-1", false)).isFalse();
        assertThat(repository.markProcessing("processing-1", true)).isTrue();
    }

    private void insert(String id,
                        String status,
                        Instant nextAttemptAt,
                        Instant createdAt,
                        Instant updatedAt) {
        jdbc.update("""
                INSERT INTO memory_projection_outbox(
                    id, aggregate_type, aggregate_id, projection_type, operation,
                    payload_json, status, attempt_count, next_attempt_at, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                "MEMORY_ENTITY",
                id,
                "VECTOR",
                "UPSERT",
                "{\"entityId\":\"%s\",\"text\":\"测试\"}".formatted(id),
                status,
                0,
                nextAttemptAt != null ? nextAttemptAt.toString() : null,
                createdAt.toString(),
                updatedAt.toString());
    }
}
