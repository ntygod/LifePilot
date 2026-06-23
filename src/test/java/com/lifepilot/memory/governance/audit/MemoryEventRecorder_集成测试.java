package com.lifepilot.memory.governance.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MemoryEventRecorder 集成测试。
 *
 * @author zsg
 * @since 2026-06-23
 */
class MemoryEventRecorder_集成测试 {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private MemoryEventRecorder recorder;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE memory_events (
                    id              TEXT PRIMARY KEY,
                    event_type      TEXT NOT NULL,
                    layer           TEXT NOT NULL,
                    session_id      TEXT,
                    conversation_id TEXT,
                    entity_id       TEXT,
                    action          TEXT NOT NULL,
                    description     TEXT,
                    metadata_json   TEXT NOT NULL DEFAULT '{}',
                    created_at      TEXT NOT NULL
                )
                """);
        recorder = new MemoryEventRecorder(jdbcTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 记录事件时metadata应按真实Json保存() throws Exception {
        recorder.record(new MemoryEvent(
                "event-1",
                "FORMATION",
                "L3",
                "web:session-1",
                "conversation-1",
                "entity-1",
                "upsert",
                "写入记忆",
                Map.of("source", Map.of("traceId", "trace-1"), "scores", List.of(0.7, 0.9)),
                Instant.parse("2026-06-23T10:00:00Z")
        ));

        String json = jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM memory_events WHERE id = ?",
                String.class,
                "event-1");
        Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() {});

        assertThat(metadata).containsKey("source").containsKey("scores");
        assertThat(((Map<?, ?>) metadata.get("source")).get("traceId")).isEqualTo("trace-1");
        assertThat((List<?>) metadata.get("scores")).hasSize(2);
    }

    @Test
    void metadata无法序列化时记录应失败() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("self", metadata);

        var event = new MemoryEvent(
                "event-broken",
                "FORMATION",
                "L3",
                "web:session-1",
                "conversation-1",
                "entity-1",
                "upsert",
                "写入记忆",
                metadata,
                Instant.parse("2026-06-23T10:00:00Z")
        );

        assertThatThrownBy(() -> recorder.record(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata JSON 序列化失败");
    }
}
