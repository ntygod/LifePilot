package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.JdbcTranscriptStore;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.memory.event.MemoryEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatSessionService transcript 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("ChatSessionService transcript 集成测试")
class ChatSessionService_Transcript集成测试 {

    private ChatSessionService chatSessionService;
    private ChatSessionRepository chatSessionRepository;
    private AttachmentRepository attachmentRepository;
    private SessionTranscriptRepository transcriptRepository;
    private JdbcTranscriptStore transcriptStore;
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
                CREATE TABLE message_attachments (
                    id TEXT PRIMARY KEY,
                    entry_id TEXT,
                    session_id TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL DEFAULT 0,
                    mime_type TEXT NOT NULL DEFAULT '',
                    url TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);

        var objectMapper = new ObjectMapper();
        var eventBus = new NoopMemoryEventBus();
        var sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        transcriptRepository = new SessionTranscriptRepository(
                jdbcTemplate, objectMapper, sessionStoreRepository, eventBus);
        chatSessionRepository = new ChatSessionRepository(sessionStoreRepository);
        attachmentRepository = new AttachmentRepository(jdbcTemplate);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, transcriptRepository, chatSessionRepository);

        SessionKnowledgeBaseRepository knowledgeBaseRepository = mock(SessionKnowledgeBaseRepository.class);
        when(knowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());

        chatSessionService = new ChatSessionService(
                chatSessionRepository,
                knowledgeBaseRepository,
                attachmentRepository,
                objectMapper,
                transcriptRepository
        );
    }

    @Test
    void getSessionMessages_优先读取Transcript并保留附件和扩展字段() {
        ChatSession session = ChatSession.createWithId("session-transcript-read", "读取测试");
        chatSessionRepository.save(session);

        String assistantMessageId = transcriptStore.appendAssistantMessage(
                session.id(),
                "这是结构化面板",
                "已生成摘要",
                "trace-read",
                """
                        {"components":[
                          {"id":"card-1","type":"Card","properties":{"title":"待办"},"children":[],"signal":null}
                        ]}
                        """,
                """
                        [{"type":"thought","content":"先整理结果"}]
                        """,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-23T08:00:00Z")
        );
        attachmentRepository.saveForEntry(
                assistantMessageId,
                session.id(),
                "todo.png",
                "/tmp/todo.png",
                128,
                "image/png",
                "http://localhost/todo.png"
        );

        var messages = chatSessionService.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().id()).isEqualTo(assistantMessageId);
        assertThat(messages.getFirst().traceId()).isEqualTo("trace-read");
        assertThat(messages.getFirst().reasoningSummary()).isEqualTo("已生成摘要");
        assertThat(messages.getFirst().a2uiComponents()).isNotNull().hasSize(1);
        assertThat(messages.getFirst().reactSteps()).isNotNull().hasSize(1);
        assertThat(messages.getFirst().attachments()).isNotNull().hasSize(1);
        assertThat(messages.getFirst().attachments().getFirst().fileName()).isEqualTo("todo.png");
    }

    @Test
    void forkSession_基于Transcript复制消息与附件() {
        ChatSession session = ChatSession.createWithId("session-transcript-fork", "分叉源会话");
        chatSessionRepository.save(session);

        String userMessageId = transcriptStore.appendUserMessage(
                session.id(),
                "先整理东京行程",
                "trace-fork",
                Instant.parse("2026-03-23T09:00:00Z")
        );
        String assistantMessageId = transcriptStore.appendAssistantMessage(
                session.id(),
                "这是初版行程建议",
                "行程摘要",
                "trace-fork",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-23T09:00:01Z")
        );
        attachmentRepository.saveForEntry(
                assistantMessageId,
                session.id(),
                "plan.md",
                "/tmp/plan.md",
                256,
                "text/markdown",
                "http://localhost/plan.md"
        );

        SessionInfo forked = chatSessionService.forkSession(session.id(), assistantMessageId, "分叉会话");
        var forkedMessages = chatSessionService.getSessionMessages(forked.id());

        assertThat(forkedMessages).hasSize(2);
        assertThat(forkedMessages).extracting(com.lifepilot.interaction.web.model.MessageInfo::content)
                .containsExactly("先整理东京行程", "这是初版行程建议");
        assertThat(forkedMessages.get(1).attachments()).isNotNull().hasSize(1);
        assertThat(forkedMessages.get(1).attachments().getFirst().fileName()).isEqualTo("plan.md");
        assertThat(forkedMessages.get(1).id()).isNotEqualTo(assistantMessageId);
        assertThat(forkedMessages.getFirst().id()).isNotEqualTo(userMessageId);
        assertThat(chatSessionRepository.findById(forked.id())).get()
                .extracting(ChatSession::messageCount)
                .isEqualTo(2);
    }

    @Test
    void clearSessionMessages_同步清空Transcript与附件() {
        ChatSession session = ChatSession.createWithId("session-transcript-clear", "清空测试");
        chatSessionRepository.save(session);

        String userMessageId = transcriptStore.appendUserMessage(
                session.id(),
                "先记录一条消息",
                "trace-clear",
                Instant.parse("2026-03-23T10:00:00Z")
        );
        attachmentRepository.saveForEntry(
                userMessageId,
                session.id(),
                "note.txt",
                "/tmp/note.txt",
                64,
                "text/plain",
                "http://localhost/note.txt"
        );

        chatSessionService.clearSessionMessages(session.id());

        assertThat(chatSessionService.getSessionMessages(session.id())).isEmpty();
        assertThat(attachmentRepository.findByEntryId(userMessageId)).isEmpty();
        assertThat(chatSessionRepository.findById(session.id())).get()
                .extracting(ChatSession::messageCount, ChatSession::summary, ChatSession::lastMessageAt)
                .containsExactly(0, null, null);
    }

    @Test
    void listSessions_优先读取SessionStore事实源() {
        Instant createdAt = Instant.parse("2026-03-23T11:00:00Z");
        ChatSession session = new ChatSession(
                "session-transcript-session-store",
                "seed-title",
                "seed-summary",
                1,
                false,
                false,
                createdAt,
                createdAt,
                createdAt
        );
        chatSessionRepository.save(session);

        jdbcTemplate.update("""
                        UPDATE session_store
                        SET title = ?, summary = ?, message_count = ?, updated_at = ?, last_activity_at = ?
                        WHERE session_id = ?
                        """,
                "source-title",
                "source-summary",
                3,
                "2026-03-23T11:30:00Z",
                "2026-03-23T11:30:00Z",
                session.id());
        var sessions = chatSessionService.listSessions();

        assertThat(sessions).hasSize(1);
        assertThat(sessions.getFirst())
                .extracting(SessionInfo::title, SessionInfo::lastMessagePreview)
                .containsExactly("source-title", "source-summary");
        assertThat(chatSessionRepository.findById(session.id())).get()
                .extracting(ChatSession::title, ChatSession::summary, ChatSession::messageCount)
                .containsExactly("source-title", "source-summary", 3);
    }

    private static final class NoopMemoryEventBus implements MemoryEventBus {

        private final List<MemoryEvent> events = new ArrayList<>();

        @Override
        public void publish(MemoryEvent event) {
            events.add(event);
        }
    }
}
