package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.JdbcTranscriptStore;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息持久化时序缺陷探索测试。
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("消息持久化时序缺陷探索测试")
class MessagePersistence_BugCondition_探索测试 {

    private JdbcTemplate jdbcTemplate;
    private ChatSessionRepository sessionRepository;
    private SessionTranscriptRepository transcriptRepository;
    private JdbcTranscriptStore transcriptStore;
    private MessageFeedbackRepository feedbackRepository;

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
                CREATE TABLE message_feedback (
                    id TEXT PRIMARY KEY,
                    entry_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    feedback TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);

        var objectMapper = new ObjectMapper();
        var sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper);
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        sessionRepository = new ChatSessionRepository(sessionStoreRepository);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, transcriptRepository, sessionRepository);
        feedbackRepository = new MessageFeedbackRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void appendAssistantMessage_返回的条目ID可立即在Transcript中查询到() {
        String sessionId = createSession();

        String assistantEntryId = transcriptStore.appendAssistantMessage(
                sessionId,
                "今天天气晴，气温 25C。",
                null,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null
        );

        assertThat(assistantEntryId).isNotBlank();
        assertThat(transcriptRepository.findById(assistantEntryId)).isPresent();
    }

    @Test
    void 消息同步写入_方法返回时已完成持久化() {
        String sessionId = createSession();

        String userEntryId = transcriptStore.appendUserMessage(sessionId, "帮我安排明天的日程", "trace-sync", null);
        assertThat(transcriptRepository.findById(userEntryId)).isPresent();

        String assistantEntryId = transcriptStore.appendAssistantMessage(
                sessionId,
                "好的，我已为你安排明天的日程。",
                null,
                "trace-sync",
                null,
                null,
                null,
                null,
                null
        );

        assertThat(transcriptRepository.findById(assistantEntryId)).isPresent();
        assertThat(transcriptRepository.findBySessionId(sessionId))
                .extracting(SessionTranscriptRepository.SessionTranscriptEntryRow::id)
                .containsExactly(userEntryId, assistantEntryId);
    }

    @Test
    void feedback使用TranscriptEntryId可直接查询会话和内容() {
        String sessionId = createSession();

        transcriptStore.appendUserMessage(sessionId, "推荐一本好书", "trace-feedback", null);
        String assistantEntryId = transcriptStore.appendAssistantMessage(
                sessionId,
                "推荐《深入理解计算机系统》。",
                null,
                "trace-feedback",
                null,
                null,
                null,
                null,
                null
        );

        assertThat(feedbackRepository.entryExists(assistantEntryId)).isTrue();
        assertThat(feedbackRepository.getSessionIdByEntryId(assistantEntryId)).isEqualTo(sessionId);
        assertThat(feedbackRepository.getEntryContentById(assistantEntryId)).isEqualTo("推荐《深入理解计算机系统》。");

        feedbackRepository.saveForEntry(assistantEntryId, sessionId, "like", "有帮助");
        assertThat(feedbackRepository.findByEntryId(assistantEntryId)).hasSize(1);
    }

    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionRepository.save(new ChatSession(
                sessionId,
                "测试会话",
                null,
                0,
                false,
                false,
                null,
                Instant.now(),
                Instant.now(),
                null
        ));
        return sessionId;
    }
}
