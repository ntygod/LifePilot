package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transcript 会话存储集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("Transcript 会话存储集成测试")
class TranscriptSessionStoreTest {

    private ChatSessionRepository chatSessionRepository;
    private SessionStoreRepository sessionStoreRepository;
    private SessionTranscriptRepository sessionTranscriptRepository;
    private JdbcTranscriptStore transcriptStore;

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
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper);
        sessionTranscriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        chatSessionRepository = new ChatSessionRepository(sessionStoreRepository);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, sessionTranscriptRepository, chatSessionRepository);
    }

    @Test
    @DisplayName("appendTurn 会同时写入 session_store 和 transcript")
    void appendTurnWritesSessionStoreAndTranscript() {
        ChatSession session = ChatSession.create("测试 Transcript");
        chatSessionRepository.save(session);

        String traceId = UUID.randomUUID().toString();
        transcriptStore.appendTurn(session.id(), "用户消息", "助手回复", "摘要", traceId);

        var sessionRow = sessionStoreRepository.findBySessionId(session.id()).orElseThrow();
        assertThat(sessionRow.messageCount()).isEqualTo(2);
        assertThat(sessionRow.summary()).isEqualTo("助手回复");
        assertThat(sessionRow.channel()).isEqualTo("web");

        var transcriptRows = sessionTranscriptRepository.findBySessionId(session.id());
        assertThat(transcriptRows).hasSize(2);
        assertThat(transcriptRows.get(0).entryType()).isEqualTo("user_message");
        assertThat(transcriptRows.get(0).traceId()).isEqualTo(traceId);
        assertThat(transcriptRows.get(0).payloadJson()).contains("用户消息");
        assertThat(transcriptRows.get(1).entryType()).isEqualTo("assistant_message");
        assertThat(transcriptRows.get(1).traceId()).isEqualTo(traceId);
        assertThat(transcriptRows.get(1).payloadJson()).contains("摘要");
        assertThat(transcriptRows.get(1).payloadJson()).contains("助手回复");
    }

    @Test
    @DisplayName("appendSystemMessage 会写入 system_event 条目")
    void appendSystemMessageWritesSystemEvent() {
        ChatSession session = ChatSession.create("系统消息测试");
        chatSessionRepository.save(session);

        transcriptStore.appendSystemMessage(session.id(), "系统提示", "trace-system", null);

        var transcriptRows = sessionTranscriptRepository.findBySessionId(session.id());
        assertThat(transcriptRows).hasSize(1);
        assertThat(transcriptRows.getFirst().entryType()).isEqualTo("system_event");
        assertThat(transcriptRows.getFirst().role()).isEqualTo("system");
        assertThat(transcriptRows.getFirst().payloadJson()).contains("系统提示");
    }

    @Test
    @DisplayName("appendUserMessage 会自动创建外部渠道会话壳")
    void appendUserMessageCreatesExternalSessionShell() {
        String sessionId = "feishu:chat-1:user-1";

        String entryId = transcriptStore.appendUserMessage(sessionId, "来自外部渠道的消息", "trace-shell", null);

        assertThat(entryId).isNotBlank();
        assertThat(sessionStoreRepository.findBySessionId(sessionId)).isPresent();
        assertThat(chatSessionRepository.findById(sessionId)).isPresent()
                .get()
                .extracting(ChatSession::title)
                .isEqualTo("飞书对话");
    }

    @Test
    @DisplayName("updateConfig 会写入 session_store")
    void updateConfigWritesSessionStore() {
        ChatSession session = new ChatSession(
                UUID.randomUUID().toString(),
                "配置测试",
                null,
                0,
                false,
                false,
                null,
                Instant.now(),
                Instant.now(),
                null
        );
        chatSessionRepository.save(session);

        chatSessionRepository.updateConfig(session.id(), Map.of(
                "preferredProviderId", "openai",
                "maxTokens", 4096
        ));

        var config = sessionStoreRepository.getConfig(session.id());
        assertThat(config).containsEntry("preferredProviderId", "openai");
        assertThat(config).containsEntry("maxTokens", 4096);
    }
}
