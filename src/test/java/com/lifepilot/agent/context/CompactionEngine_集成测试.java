package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.document.MemoryDocumentRepository;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompactionEngine 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("CompactionEngine 集成测试")
class CompactionEngine_集成测试 {

    private static final String SESSION_ID = "web:compaction-session";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SessionStoreRepository sessionStoreRepository;
    private SessionTranscriptRepository transcriptRepository;
    private MemoryDocumentRepository memoryDocumentRepository;

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

        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper);
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        memoryDocumentRepository = new MemoryDocumentRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
    }

    @Test
    void compactIfNeeded_会写入压缩摘要并推进边界() throws Exception {
        var config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(120);
        config.getContext().getCompaction().setTriggerThresholdPercent(10);
        config.getContext().getCompaction().setKeepRecentTurns(1);
        config.getContext().getCompaction().setMinTurnCount(2);
        config.getContext().getCompaction().setMaxSourceEntries(20);
        config.getContext().getCompaction().setSummaryMaxChars(120);

        String turn1UserId = appendMessage("user", "第一轮问题，内容比较长，需要进入压缩范围。", "turn-1", Instant.parse("2026-03-23T09:00:00Z"));
        appendMessage("assistant", "第一轮回答，也比较长，用于推高 token 估算。", "turn-1", Instant.parse("2026-03-23T09:00:05Z"));
        appendMessage("user", "第二轮问题，继续补充更多背景与限制条件。", "turn-2", Instant.parse("2026-03-23T09:01:00Z"));
        appendMessage("assistant", "第二轮回答，已经执行了一些分析步骤。", "turn-2", Instant.parse("2026-03-23T09:01:05Z"));
        transcriptRepository.appendEntry(
                SESSION_ID,
                TranscriptEntryType.TOOL_RESULT,
                "tool",
                "turn-2",
                "trace-compact",
                true,
                false,
                Map.of(
                        "toolId", "tool.search",
                        "success", true,
                        "outputJson", "{\"summary\":\"命中 3 条资料，包含价格、风险和时间线。\"}"
                ),
                Instant.parse("2026-03-23T09:01:10Z")
        );
        String turn3UserId = appendMessage("user", "第三轮问题，属于最近轮次，应当保留为原始 transcript。", "turn-3", Instant.parse("2026-03-23T09:02:00Z"));
        appendMessage("assistant", "第三轮回答，作为最近轮次保留。", "turn-3", Instant.parse("2026-03-23T09:02:05Z"));

        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(eq("memory/compression-summary"), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vars = invocation.getArgument(1, Map.class);
                    return "压缩提示:\n" + vars.get("conversation");
                });

        var generationRouter = mock(GenerationRouter.class);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse(
                        "这是压缩摘要，保留关键决策、约束和工具结果。",
                        120,
                        30,
                        "provider-x",
                        "model-x",
                        20,
                        false
                ));

        var engine = new CompactionEngine(
                config,
                transcriptRepository,
                sessionStoreRepository,
                new TranscriptCompactionBoundaryResolver(objectMapper),
                promptRegistry,
                generationRouter,
                objectMapper,
                new PreCompactionMemoryFlushEngine(
                        memoryDocumentRepository,
                        transcriptRepository,
                        sessionStoreRepository,
                        objectMapper
                )
        );

        boolean compacted = engine.compactIfNeeded(SESSION_ID, "trace-compact");

        assertThat(compacted).isTrue();
        var rows = transcriptRepository.findBySessionId(SESSION_ID);
        var flushRow = rows.get(rows.size() - 2);
        var summaryRow = rows.getLast();
        assertThat(flushRow.entryType()).isEqualTo(TranscriptEntryType.MEMORY_FLUSH_EVENT.value());
        assertThat(summaryRow.entryType()).isEqualTo(TranscriptEntryType.COMPACTION_SUMMARY.value());
        Map<String, Object> flushPayload = objectMapper.readValue(flushRow.payloadJson(), new TypeReference<>() { });
        Map<String, Object> payload = objectMapper.readValue(summaryRow.payloadJson(), new TypeReference<>() { });
        assertThat(flushPayload).containsKey("documentIds");
        assertThat(payload)
                .containsEntry("summary", "这是压缩摘要，保留关键决策、约束和工具结果。")
                .containsEntry("firstKeptEntryId", turn3UserId)
                .containsEntry("sourceStartEntryId", turn1UserId);
        var sessionRow = sessionStoreRepository.findBySessionId(SESSION_ID).orElseThrow();
        assertThat(sessionRow.compactionCount()).isEqualTo(1);
        assertThat(sessionRow.memoryFlushAt()).isNotNull();
        @SuppressWarnings("unchecked")
        var documentIds = (java.util.List<Object>) flushPayload.get("documentIds");
        String documentId = documentIds.getFirst().toString();
        var documentRow = memoryDocumentRepository.listByNamespace("transcript").getFirst();
        assertThat(documentRow.id()).isEqualTo(documentId);
        assertThat(documentRow.contentMarkdown())
                .contains("压缩前会话片段")
                .contains("第一轮问题")
                .contains("tool.search");
        assertThat(memoryDocumentRepository.findChunks(documentId)).isNotEmpty();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationRouter).call(anyString(), promptCaptor.capture(), any(), any(), any(), any(), any());
        assertThat(promptCaptor.getValue())
                .contains("[新增待压缩内容]")
                .contains("第一轮问题")
                .contains("第二轮回答")
                .contains("tool.search")
                .doesNotContain("第三轮回答");
    }

    private String appendMessage(String role, String content, String turnId, Instant createdAt) {
        return transcriptRepository.appendMessageEntry(
                SESSION_ID,
                role,
                content,
                null,
                "trace-compact",
                null,
                null,
                null,
                null,
                createdAt
        );
    }
}
