package com.lifepilot.conversation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.memory.document.MemoryDocumentRepository;
import com.lifepilot.memory.event.MemoryEvent;
import com.lifepilot.memory.event.MemoryEventBus;
import com.lifepilot.observability.context.ContextReportRepository;
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
 * Transcript 支撑存储集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("Transcript 支撑存储集成测试")
class TranscriptSupportingStores_集成测试 {

    private SessionArtifactRepository artifactRepository;
    private MemoryDocumentRepository memoryDocumentRepository;
    private ContextReportRepository contextReportRepository;
    private SessionStoreRepository sessionStoreRepository;
    private CollectingMemoryEventBus eventBus;

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
                CREATE TABLE session_artifacts (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    source_entry_id TEXT,
                    trace_id TEXT,
                    artifact_type TEXT NOT NULL,
                    title TEXT,
                    summary TEXT,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE,
                    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_documents (
                    id TEXT PRIMARY KEY,
                    namespace TEXT NOT NULL,
                    doc_type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    path_like_key TEXT NOT NULL,
                    content_markdown TEXT NOT NULL,
                    source_session_id TEXT,
                    source_entry_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (source_session_id) REFERENCES session_store(session_id) ON DELETE SET NULL,
                    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE SET NULL,
                    UNIQUE(namespace, path_like_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_document_chunks (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content_text TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (document_id) REFERENCES memory_documents(id) ON DELETE CASCADE,
                    UNIQUE(document_id, chunk_index)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE context_reports (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    trace_id TEXT,
                    system_prompt_tokens INTEGER NOT NULL DEFAULT 0,
                    transcript_tokens INTEGER NOT NULL DEFAULT 0,
                    memory_tokens INTEGER NOT NULL DEFAULT 0,
                    artifact_tokens INTEGER NOT NULL DEFAULT 0,
                    tool_schema_tokens INTEGER NOT NULL DEFAULT 0,
                    tool_result_tokens INTEGER NOT NULL DEFAULT 0,
                    pruning_applied INTEGER NOT NULL DEFAULT 0,
                    compaction_applied INTEGER NOT NULL DEFAULT 0,
                    context_window INTEGER NOT NULL DEFAULT 0,
                    reserved_tokens INTEGER NOT NULL DEFAULT 0,
                    payload_json TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);

        eventBus = new CollectingMemoryEventBus();
        var objectMapper = new ObjectMapper();
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        artifactRepository = new SessionArtifactRepository(jdbcTemplate, objectMapper, sessionStoreRepository, eventBus);
        memoryDocumentRepository = new MemoryDocumentRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        contextReportRepository = new ContextReportRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
    }

    @Test
    void artifactStore_保存后可读取并发布事件() {
        String artifactId = artifactRepository.save(
                "web:artifact-session",
                null,
                "trace-artifact-1",
                "tool_result",
                "网页截图",
                "保存一张截图",
                Map.of("mimeType", "image/png", "size", 1024),
                "ACTIVE",
                Instant.parse("2026-03-23T11:00:00Z")
        );

        var row = artifactRepository.findById(artifactId).orElseThrow();

        assertThat(row.sessionId()).isEqualTo("web:artifact-session");
        assertThat(row.artifactType()).isEqualTo("tool_result");
        assertThat(artifactRepository.readPayload(artifactId))
                .containsEntry("mimeType", "image/png")
                .containsEntry("size", 1024);
        assertThat(eventBus.events())
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("SessionStarted", "ArtifactCommitted");
    }

    @Test
    void memoryDocumentStore_支持upsert与chunk替换() {
        String documentId = memoryDocumentRepository.upsert(
                "memory",
                "daily_log",
                "2026-03-23 日志",
                "daily/2026-03-23",
                "# 今日记录\n- 完成 transcript 底座",
                "web:memory-session",
                null,
                Instant.parse("2026-03-23T11:05:00Z")
        );
        memoryDocumentRepository.replaceChunks(documentId, List.of(
                "第一段：完成 transcript 底座",
                "第二段：开始铺事件总线"
        ), Instant.parse("2026-03-23T11:05:01Z"));

        var row = memoryDocumentRepository
                .findByNamespaceAndPathLikeKey("memory", "daily/2026-03-23")
                .orElseThrow();
        var chunks = memoryDocumentRepository.findChunks(documentId);

        assertThat(row.id()).isEqualTo(documentId);
        assertThat(row.sourceSessionId()).isEqualTo("web:memory-session");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(1).contentText()).contains("事件总线");
    }

    @Test
    void contextReportStore_保存后可按会话读取最新报告() {
        String reportId = contextReportRepository.save(
                "web:context-session",
                "trace-context-1",
                1200,
                2400,
                300,
                500,
                200,
                180,
                true,
                false,
                32768,
                4096,
                Map.of("provider", "openai", "model", "gpt-5.4"),
                Instant.parse("2026-03-23T11:10:00Z")
        );

        var row = contextReportRepository.findLatestBySessionId("web:context-session").orElseThrow();
        var session = sessionStoreRepository.findBySessionId("web:context-session").orElseThrow();

        assertThat(row.id()).isEqualTo(reportId);
        assertThat(row.traceId()).isEqualTo("trace-context-1");
        assertThat(row.systemPromptTokens()).isEqualTo(1200);
        assertThat(row.toolResultTokens()).isEqualTo(180);
        assertThat(row.pruningApplied()).isTrue();
        assertThat(session.contextTokensEstimate()).isEqualTo(1200 + 2400 + 300 + 200 + 180);
        assertThat(contextReportRepository.readPayload(reportId))
                .containsEntry("provider", "openai")
                .containsEntry("model", "gpt-5.4");
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
