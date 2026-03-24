package com.lifepilot.memory.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InjectionRecordRepository 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("InjectionRecordRepository 集成测试")
class InjectionRecordRepository_集成测试 {

    private InjectionRecordRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        jdbcTemplate.execute("""
                CREATE TABLE session_store (
                    session_id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL DEFAULT 'web',
                    chat_type TEXT NOT NULL DEFAULT 'chat',
                    title TEXT NOT NULL,
                    summary TEXT,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_activity_at TEXT NOT NULL,
                    provider_override TEXT,
                    model_override TEXT,
                    thinking_level TEXT,
                    reasoning_level TEXT,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    context_tokens_estimate INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    total_tokens INTEGER NOT NULL DEFAULT 0,
                    compaction_count INTEGER NOT NULL DEFAULT 0,
                    memory_flush_at TEXT,
                    active_branch_id TEXT NOT NULL DEFAULT 'main'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE session_transcript_entries (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    parent_id TEXT,
                    branch_id TEXT NOT NULL DEFAULT 'main',
                    entry_type TEXT NOT NULL,
                    role TEXT,
                    turn_id TEXT,
                    trace_id TEXT,
                    visible_to_model INTEGER NOT NULL DEFAULT 1,
                    visible_to_user INTEGER NOT NULL DEFAULT 1,
                    payload_json TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_injection_records (
                    id TEXT PRIMARY KEY,
                    source_entry_id TEXT,
                    session_id TEXT NOT NULL,
                    entity_ids_json TEXT NOT NULL,
                    entity_type TEXT NOT NULL DEFAULT 'GENERAL',
                    source_trace_id TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE
                )
                """);

        jdbcTemplate.update("""
                        INSERT INTO session_store (
                            session_id, title, created_at, updated_at, last_activity_at
                        ) VALUES (?, ?, '2026-03-23T10:00:00Z', '2026-03-23T10:00:00Z', '2026-03-23T10:00:00Z')
                        """,
                "session-1", "测试会话");
        jdbcTemplate.update("""
                        INSERT INTO session_transcript_entries (
                            id, session_id, branch_id, entry_type, role, payload_json, created_at
                        ) VALUES (?, ?, 'main', 'assistant_message', 'assistant', '{"content":"你好"}', '2026-03-23T10:00:01Z')
                        """,
                "entry-1", "session-1");

        repository = new InjectionRecordRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void save_按SourceEntryId保存并可查询() {
        repository.save("entry-1", "session-1", "trace-1", java.util.List.of("e1", "e2"));

        assertThat(repository.findEntityIdsBySourceEntryId("entry-1"))
                .containsExactlyInAnyOrder("e1", "e2");
    }

    @Test
    void saveWithType_按SourceTraceId保存经验注入记录() {
        repository.saveWithType("trace-exp", "session-1", java.util.List.of("exp-1", "exp-2"), "EXPERIENCE");

        assertThat(repository.findEntityIdsBySourceTraceIdAndType("trace-exp", "EXPERIENCE"))
                .containsExactlyInAnyOrder("exp-1", "exp-2");
    }
}
