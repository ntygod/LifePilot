package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.JdbcTranscriptStore;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息持久化保持测试。
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("消息持久化保持测试")
class MessagePersistence_Preservation_保持测试 {

    private ChatSessionRepository sessionRepository;
    private SessionStoreRepository sessionStoreRepository;
    private SessionTranscriptRepository transcriptRepository;
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
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        sessionRepository = new ChatSessionRepository(sessionStoreRepository);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, transcriptRepository, sessionRepository);
    }

    @Test
    void appendTurn_已存在会话中按顺序写入两条消息() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "测试会话");
        sessionRepository.save(session);

        transcriptStore.appendTurn(session.id(), "帮我查一下明天的天气", "明天北京多云，气温 18-26°C。", null, "trace-1");

        var messages = transcriptRepository.findUserConversationRowsBySessionId(session.id());
        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(SessionTranscriptRepository.TranscriptMessageViewRow::role)
                .containsExactly("user", "assistant");
        assertThat(messages).extracting(SessionTranscriptRepository.TranscriptMessageViewRow::content)
                .containsExactly("帮我查一下明天的天气", "明天北京多云，气温 18-26°C。");
    }

    @Test
    void appendAssistantMessage_保留A2uiTrace与摘要字段() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "A2UI 会话");
        sessionRepository.save(session);

        String a2uiJson = """
                {"components":[
                  {"id":"card-1","type":"Card","properties":{"title":"待办面板"},"children":["btn-1"],"signal":null},
                  {"id":"btn-1","type":"Button","properties":{"label":"刷新"},"children":[],"signal":{"name":"panel.refresh","payload":{"section":"todos"}}}
                ]}
                """;

        String entryId = transcriptStore.appendAssistantMessage(
                session.id(),
                "这是当前面板",
                "已生成结构化面板",
                "trace-a2ui-1",
                a2uiJson,
                "[{\"type\":\"thought\",\"content\":\"先整理结构\"}]",
                CompletionMode.NORMAL,
                null,
                null
        );

        var row = transcriptRepository.findById(entryId).orElseThrow();
        assertThat(row.traceId()).isEqualTo("trace-a2ui-1");
        assertThat(row.payloadJson()).contains("已生成结构化面板");
        assertThat(row.payloadJson()).contains("card-1");
        assertThat(row.payloadJson()).contains("panel.refresh");
    }

    @Test
    void 删除会话时级联删除Transcript消息() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "级联删除");
        sessionRepository.save(session);

        transcriptStore.appendTurn(session.id(), "第一条", "第二条", null, "trace-delete");
        assertThat(transcriptRepository.findBySessionId(session.id())).hasSize(2);

        sessionRepository.deleteById(session.id());

        assertThat(transcriptRepository.findBySessionId(session.id())).isEmpty();
        assertThat(sessionStoreRepository.findBySessionId(session.id())).isEmpty();
    }
}
