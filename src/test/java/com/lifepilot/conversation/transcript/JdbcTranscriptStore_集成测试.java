package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.memory.store.event.MemoryEvent;
import com.lifepilot.memory.store.event.MemoryEventBus;
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
    private JdbcTemplate jdbcTemplate;
    private SessionStoreRepository sessionStoreRepository;
    private SessionTranscriptRepository sessionTranscriptRepository;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
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
                    active_branch_id TEXT NOT NULL DEFAULT 'main',
                    project_id TEXT
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
                CREATE VIRTUAL TABLE session_transcript_entries_fts USING fts5(
                    entry_id UNINDEXED,
                    session_id UNINDEXED,
                    role UNINDEXED,
                    content,
                    tokenize='unicode61 remove_diacritics 2'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_ai
                AFTER INSERT ON session_transcript_entries
                BEGIN
                    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
                    SELECT new.rowid,
                           new.id,
                           new.session_id,
                           COALESCE(new.role, ''),
                           json_extract(new.payload_json, '$.content')
                    WHERE new.entry_type IN ('user_message', 'assistant_message')
                      AND new.visible_to_user = 1
                      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_ad
                AFTER DELETE ON session_transcript_entries
                BEGIN
                    DELETE FROM session_transcript_entries_fts
                    WHERE rowid = old.rowid
                      AND old.entry_type IN ('user_message', 'assistant_message')
                      AND old.visible_to_user = 1
                      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_au
                AFTER UPDATE ON session_transcript_entries
                BEGIN
                    DELETE FROM session_transcript_entries_fts
                    WHERE rowid = old.rowid
                      AND old.entry_type IN ('user_message', 'assistant_message')
                      AND old.visible_to_user = 1
                      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';

                    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
                    SELECT new.rowid,
                           new.id,
                           new.session_id,
                           COALESCE(new.role, ''),
                           json_extract(new.payload_json, '$.content')
                    WHERE new.entry_type IN ('user_message', 'assistant_message')
                      AND new.visible_to_user = 1
                      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
                END
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
                "todo.create",
                "call-1",
                "创建待办",
                "{\"title\":\"收拾工位\"}",
                Instant.parse("2026-03-23T12:05:00Z")
        );
        transcriptStore.appendToolResult(
                "web:tool-store-session",
                "turn-1",
                "trace-2",
                "todo.create",
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

    @Test
    void appendCustomMessage_保留turnId并可见给用户() {
        transcriptStore.appendAssistantMessage(
                "web:permission-session",
                "turn-permission-1",
                "我来检查 gh CLI 是否可用。",
                null,
                "trace-permission",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-25T13:00:00Z")
        );
        transcriptStore.appendCustomMessage(
                "web:permission-session",
                "turn-permission-1",
                "permission-approval",
                "{\"requestId\":\"req-1\",\"toolId\":\"shell.exec\"}",
                "trace-permission",
                false,
                true,
                Instant.parse("2026-03-25T13:00:01Z")
        );

        var transcriptRows = sessionTranscriptRepository.findUserConversationRowsBySessionId("web:permission-session");

        assertThat(transcriptRows).hasSize(2);
        assertThat(transcriptRows.get(1))
                .extracting(
                        SessionTranscriptRepository.TranscriptMessageViewRow::role,
                        SessionTranscriptRepository.TranscriptMessageViewRow::turnId,
                        SessionTranscriptRepository.TranscriptMessageViewRow::content
                )
                .containsExactly(
                        "permission-approval",
                        "turn-permission-1",
                        "{\"requestId\":\"req-1\",\"toolId\":\"shell.exec\"}"
                );
    }

    @Test
    void deleteBySessionId_会同步移除TranscriptFts索引() {
        transcriptStore.appendUserMessage(
                "web:delete-session",
                "记录一条会被删除的用户消息",
                "trace-delete",
                Instant.parse("2026-03-23T12:10:00Z")
        );
        transcriptStore.appendAssistantMessage(
                "web:delete-session",
                "这条助手消息也应一起清理",
                null,
                "trace-delete",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-23T12:10:01Z")
        );

        assertThat(sessionTranscriptRepository.deleteBySessionId("web:delete-session")).isEqualTo(2);
        Integer ftsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_transcript_entries_fts WHERE session_id = ?",
                Integer.class,
                "web:delete-session"
        );
        assertThat(ftsCount).isZero();
    }

    private static final class NoopMemoryEventBus implements MemoryEventBus {

        private final List<MemoryEvent> events = new ArrayList<>();

        @Override
        public void publish(MemoryEvent event) {
            events.add(event);
        }
    }
}
