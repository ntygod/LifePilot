package com.lifepilot.memory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆事件总线分发测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("记忆事件总线分发测试")
class MemoryEventBusDispatchTest {

    private CollectingMemoryEventBus eventBus;
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

        eventBus = new CollectingMemoryEventBus();
        var objectMapper = new ObjectMapper();
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        sessionTranscriptRepository = new SessionTranscriptRepository(
                jdbcTemplate,
                objectMapper,
                sessionStoreRepository,
                eventBus
        );
    }

    @Test
    void ensureSessionShell_发布SessionStarted事件() {
        sessionStoreRepository.ensureSessionShell("web:test-session");

        assertThat(eventBus.events())
                .singleElement()
                .isInstanceOfSatisfying(MemoryEvent.SessionStarted.class, event -> {
                    assertThat(event.sessionId()).isEqualTo("web:test-session");
                    assertThat(event.channel()).isEqualTo("web");
                    assertThat(event.title()).isEqualTo("New Chat");
                });
    }

    @Test
    void appendAssistantMessageEntry_发布Transcript与Turn事件() {
        sessionTranscriptRepository.appendMessageEntry(
                "web:assistant-session",
                "assistant",
                "这是助手回复",
                "这是摘要",
                "trace-1",
                null,
                null,
                CompletionMode.NORMAL,
                null,
                Instant.parse("2026-03-23T10:00:00Z")
        );

        assertThat(eventBus.events())
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "SessionStarted",
                        "TranscriptEntryCommitted",
                        "AssistantReplyCommitted",
                        "TurnCommitted"
                );

        assertThat(eventBus.events())
                .filteredOn(MemoryEvent.AssistantReplyCommitted.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(MemoryEvent.AssistantReplyCommitted.class, event -> {
                    assertThat(event.sessionId()).isEqualTo("web:assistant-session");
                    assertThat(event.traceId()).isEqualTo("trace-1");
                    assertThat(event.assistantText()).isEqualTo("这是助手回复");
                    assertThat(event.reasoningSummary()).isEqualTo("这是摘要");
                });
    }

    @Test
    void appendToolResultEntry_发布ToolResultCommitted事件() {
        sessionTranscriptRepository.appendEntry(
                "web:tool-session",
                TranscriptEntryType.TOOL_RESULT,
                "tool",
                "turn-2",
                "trace-2",
                true,
                false,
                Map.of(
                        "toolId", "todo.create",
                        "callId", "call-1",
                        "success", true,
                        "outputJson", "{\"id\":\"todo-1\"}",
                        "artifactId", "artifact-1"
                ),
                Instant.parse("2026-03-23T10:05:00Z")
        );

        assertThat(eventBus.events())
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "SessionStarted",
                        "TranscriptEntryCommitted",
                        "ToolResultCommitted"
                );

        assertThat(eventBus.events())
                .filteredOn(MemoryEvent.ToolResultCommitted.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(MemoryEvent.ToolResultCommitted.class, event -> {
                    assertThat(event.toolId()).isEqualTo("todo.create");
                    assertThat(event.callId()).isEqualTo("call-1");
                    assertThat(event.success()).isTrue();
                    assertThat(event.artifactId()).isEqualTo("artifact-1");
                });
    }

    private static final class CollectingMemoryEventBus implements MemoryEventBus {

        private final List<MemoryEvent> events = new ArrayList<>();

        @Override
        public void publish(MemoryEvent event) {
            events.add(event);
        }

        List<MemoryEvent> events() {
            return List.copyOf(events);
        }
    }
}
