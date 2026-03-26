package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.JdbcTranscriptStore;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.memory.event.MemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatTurnService 集成测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@DisplayName("ChatTurnService 集成测试")
class ChatTurnService_集成测试 {

    private ChatTurnService chatTurnService;
    private ChatTurnRepository chatTurnRepository;
    private SessionTranscriptRepository transcriptRepository;
    private JdbcTranscriptStore transcriptStore;
    private ChatSessionRepository chatSessionRepository;
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.execute("""
                CREATE TABLE chat_turns (
                    turn_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL REFERENCES session_store(session_id) ON DELETE CASCADE,
                    last_action TEXT NOT NULL,
                    status TEXT NOT NULL,
                    request_payload_json TEXT NOT NULL,
                    user_entry_id TEXT,
                    assistant_entry_id TEXT,
                    latest_trace_id TEXT,
                    resumed_from_trace_id TEXT,
                    completion_mode TEXT,
                    last_error_code INTEGER,
                    last_error_message TEXT,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);

        var objectMapper = new ObjectMapper();
        var eventBus = new MemoryEventBus() {
            @Override
            public void publish(com.lifepilot.memory.event.MemoryEvent event) {
            }
        };
        var sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository, eventBus);
        chatSessionRepository = new ChatSessionRepository(sessionStoreRepository);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, transcriptRepository, chatSessionRepository);
        chatTurnRepository = new ChatTurnRepository(jdbcTemplate);
        chatTurnService = new ChatTurnService(chatTurnRepository, transcriptRepository, objectMapper);

        chatSessionRepository.save(ChatSession.createWithId("session-turn-retry", "turn 测试"));
    }

    @Test
    void retry准备会隐藏旧Assistant并复用原始请求快照() {
        var sendRequest = new ChatRequest(
                "turn-1",
                ChatTurnAction.SEND,
                "请总结今天的新闻",
                "session-turn-retry",
                List.of("att-1"),
                "provider-a"
        );
        var resolved = chatTurnService.prepare("session-turn-retry", sendRequest);
        assertThat(resolved.content()).isEqualTo("请总结今天的新闻");

        chatTurnService.bindUserEntry("session-turn-retry", "turn-1", "user-entry-1");
        String assistantEntryId = transcriptStore.appendAssistantMessage(
                "session-turn-retry",
                "turn-1",
                "第一次回答",
                null,
                "trace-1",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.now()
        );
        chatTurnService.markCompleted(
                "session-turn-retry",
                "turn-1",
                ChatTurnStatus.SUCCESS,
                assistantEntryId,
                "trace-1",
                null,
                CompletionMode.NORMAL
        );

        var replay = chatTurnService.prepare(
                "session-turn-retry",
                new ChatRequest("turn-1", ChatTurnAction.RETRY, null, "session-turn-retry", null, null)
        );

        assertThat(replay.action()).isEqualTo(ChatTurnAction.RETRY);
        assertThat(replay.content()).isEqualTo("请总结今天的新闻");
        assertThat(replay.attachmentIds()).containsExactly("att-1");
        assertThat(replay.preferredProvider()).isEqualTo("provider-a");

        var turn = chatTurnRepository.findBySessionIdAndTurnId("session-turn-retry", "turn-1").orElseThrow();
        assertThat(turn.userEntryId()).isEqualTo("user-entry-1");
        assertThat(turn.assistantEntryId()).isNull();
        assertThat(turn.status()).isEqualTo(ChatTurnStatus.PENDING);
        assertThat(turn.attemptCount()).isEqualTo(2);

        var assistantRow = transcriptRepository.findById(assistantEntryId).orElseThrow();
        assertThat(assistantRow.visibleToModel()).isFalse();
        assertThat(assistantRow.visibleToUser()).isFalse();
        Integer ftsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_transcript_entries_fts WHERE entry_id = ?",
                Integer.class,
                assistantEntryId
        );
        assertThat(ftsCount).isZero();
    }

    @Test
    void resume准备应保留旧挂起Assistant并合并用户补充信息() {
        var sendRequest = new ChatRequest(
                "turn-2",
                ChatTurnAction.SEND,
                "请帮我继续推进 GitHub 自动化链路",
                "session-turn-retry",
                List.of("att-origin"),
                "provider-a"
        );
        chatTurnService.prepare("session-turn-retry", sendRequest);

        String assistantEntryId = transcriptStore.appendAssistantMessage(
                "session-turn-retry",
                "turn-2",
                "本轮处理已挂起，等待你补充仓库地址。",
                null,
                "trace-suspended",
                null,
                null,
                CompletionMode.SUSPENDED,
                null,
                Instant.now()
        );
        chatTurnService.markCompleted(
                "session-turn-retry",
                "turn-2",
                ChatTurnStatus.SUSPENDED,
                assistantEntryId,
                "trace-suspended",
                null,
                CompletionMode.SUSPENDED
        );

        var replay = chatTurnService.prepare(
                "session-turn-retry",
                new ChatRequest(
                        "turn-2",
                        ChatTurnAction.RESUME,
                        "仓库地址是 https://github.com/acme/demo.git",
                        "session-turn-retry",
                        List.of("att-extra"),
                        null
                )
        );

        assertThat(replay.action()).isEqualTo(ChatTurnAction.RESUME);
        assertThat(replay.content())
                .contains("请帮我继续推进 GitHub 自动化链路")
                .contains("<resume_user_input>")
                .contains("https://github.com/acme/demo.git");
        assertThat(replay.attachmentIds()).containsExactly("att-origin", "att-extra");

        var turn = chatTurnRepository.findBySessionIdAndTurnId("session-turn-retry", "turn-2").orElseThrow();
        assertThat(turn.assistantEntryId()).isNull();
        assertThat(turn.status()).isEqualTo(ChatTurnStatus.PENDING);
        assertThat(turn.attemptCount()).isEqualTo(2);

        var assistantRow = transcriptRepository.findById(assistantEntryId).orElseThrow();
        assertThat(assistantRow.visibleToModel()).isTrue();
        assertThat(assistantRow.visibleToUser()).isTrue();
    }
}
