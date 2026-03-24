package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.memory.event.MemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcTranscriptStore 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("JdbcTranscriptStore 集成测试")
class JdbcTranscriptStore_集成测试 {

    private JdbcTranscriptStore transcriptStore;
    private SessionStoreRepository sessionStoreRepository;
    private SessionTranscriptRepository sessionTranscriptRepository;

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

        var objectMapper = new ObjectMapper();
        var eventBus = new NoopMemoryEventBus();
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        sessionTranscriptRepository = new SessionTranscriptRepository(
                jdbcTemplate,
                objectMapper,
                sessionStoreRepository,
                eventBus
        );
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, sessionTranscriptRepository);
    }

    @Test
    void appendMessages_更新sessionStore元数据并写入transcript() {
        transcriptStore.appendUserMessage(
                "web:store-session",
                "用户想安排下周计划",
                "trace-1",
                Instant.parse("2026-03-23T12:00:00Z")
        );
        transcriptStore.appendAssistantMessage(
                "web:store-session",
                "我已经整理出一版计划草案",
                "这是规划摘要",
                "trace-1",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-23T12:00:01Z")
        );
        transcriptStore.appendSystemMessage(
                "web:store-session",
                "系统提示：你有一个待确认事项",
                "trace-1",
                Instant.parse("2026-03-23T12:00:02Z")
        );

        var sessionRow = sessionStoreRepository.findBySessionId("web:store-session").orElseThrow();
        var transcriptRows = sessionTranscriptRepository.findBySessionId("web:store-session");

        assertThat(sessionRow.messageCount()).isEqualTo(3);
        assertThat(sessionRow.summary()).isEqualTo("系统提示：你有一个待确认事项");
        assertThat(transcriptRows).hasSize(3);
        assertThat(transcriptRows).extracting(SessionTranscriptRepository.SessionTranscriptEntryRow::entryType)
                .containsExactly("user_message", "assistant_message", "system_event");
    }

    @Test
    void appendToolEntries_只更新活跃时间不增加消息数() {
        transcriptStore.appendToolCall(
                "web:tool-store-session",
                "turn-1",
                "trace-2",
                "builtin.todo.create",
                "call-1",
                "创建待办",
                "{\"title\":\"收拾工位\"}",
                Instant.parse("2026-03-23T12:05:00Z")
        );
        transcriptStore.appendToolResult(
                "web:tool-store-session",
                "turn-1",
                "trace-2",
                "builtin.todo.create",
                "call-1",
                true,
                "{\"id\":\"todo-1\"}",
                null,
                true,
                false,
                Instant.parse("2026-03-23T12:05:01Z")
        );

        var sessionRow = sessionStoreRepository.findBySessionId("web:tool-store-session").orElseThrow();
        var transcriptRows = sessionTranscriptRepository.findBySessionId("web:tool-store-session");

        assertThat(sessionRow.messageCount()).isZero();
        assertThat(sessionRow.lastActivityAt()).isEqualTo(Instant.parse("2026-03-23T12:05:01Z"));
        assertThat(transcriptRows).hasSize(2);
        assertThat(transcriptRows).extracting(SessionTranscriptRepository.SessionTranscriptEntryRow::entryType)
                .containsExactly("tool_call", "tool_result");
        assertThat(transcriptRows.get(1).visibleToUser()).isFalse();
    }

    private static final class NoopMemoryEventBus implements MemoryEventBus {

        private final List<com.lifepilot.memory.event.MemoryEvent> events = new ArrayList<>();

        @Override
        public void publish(com.lifepilot.memory.event.MemoryEvent event) {
            events.add(event);
        }
    }
}
