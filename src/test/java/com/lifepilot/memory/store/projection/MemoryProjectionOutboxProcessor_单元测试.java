package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * MemoryProjectionOutboxProcessor 单元测试。
 *
 * @author zsg
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension.class)
class MemoryProjectionOutboxProcessor_单元测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MemoryProjectionOutboxRepository repository;
    private MemoryProjectionOutboxProcessor processor;

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
        processor = new MemoryProjectionOutboxProcessor(repository, vectorSearcher, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 投影执行失败应标记FAILED并继续抛出() {
        String outboxId = repository.enqueue(
                "MEMORY_ENTITY",
                "entity-1",
                "VECTOR",
                "UPSERT",
                Map.of("entityId", "entity-1", "text", "测试文本"));
        doThrow(new IllegalStateException("向量写入失败"))
                .when(vectorSearcher)
                .upsertEntityVector(eq("entity-1"), eq("测试文本"));

        assertThatThrownBy(() -> processor.processOne(outboxId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("记忆投影任务执行失败")
                .hasRootCauseMessage("向量写入失败");

        var row = jdbc.queryForMap(
                "SELECT status, attempt_count, last_error FROM memory_projection_outbox WHERE id = ?",
                outboxId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row.get("last_error")).asString().contains("向量写入失败");
    }

    @Test
    void 缺失任务标记PROCESSED应直接失败() {
        assertThatThrownBy(() -> repository.markProcessed("missing-id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标记 PROCESSED 失败");
    }

    @Test
    void 缺失任务标记FAILED应直接失败() {
        assertThatThrownBy(() -> repository.markFailed("missing-id", 0, "失败"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标记 FAILED 失败");
    }

    @Test
    void 缺失任务执行应直接失败() {
        assertThatThrownBy(() -> processor.processOne("missing-id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("记忆投影任务不存在: id=missing-id");
    }

    @Test
    void 非法扫描limit应直接失败() {
        assertThatThrownBy(() -> repository.findDueTaskIds(0, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit 必须大于 0");
    }
}
