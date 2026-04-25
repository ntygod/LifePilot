package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.memory.event.MemoryEvent;
import com.lifepilot.memory.event.MemoryEventBus;
import com.lifepilot.observability.context.ContextReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextEngine 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
@DisplayName("ContextEngine 集成测试")
class ContextEngine_集成测试 {

    private static final String SESSION_ID = "web:context-engine-session";

    private ContextEngine contextEngine;
    private SessionTranscriptRepository transcriptRepository;
    private SessionArtifactRepository artifactRepository;
    private ContextReportRepository contextReportRepository;

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

        var objectMapper = new ObjectMapper();
        var eventBus = new CollectingMemoryEventBus();
        var sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper, eventBus);
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository, eventBus);
        artifactRepository = new SessionArtifactRepository(jdbcTemplate, objectMapper, sessionStoreRepository, eventBus);
        contextReportRepository = new ContextReportRepository(jdbcTemplate, objectMapper, sessionStoreRepository);

        var config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(4096);
        config.getContext().setOutputReservedTokens(512);
        config.getContext().getTokenAllocation().setToolResultPercent(20);
        config.getContext().getTokenAllocation().setMemoryPercent(15);
        config.getContext().getSlice().setRecentTurnLimit(2);
        config.getContext().getSlice().setRecentArtifactLimit(2);
        config.getContext().getPruning().setRecentToolResultLimit(10);
        config.getContext().getPruning().setToolResultPreviewChars(80);

        contextEngine = new ContextEngine(
                config,
                new SessionPruningEngine(config, objectMapper),
                new TranscriptCompactionBoundaryResolver(objectMapper),
                transcriptRepository,
                null,
                null,
                artifactRepository,
                contextReportRepository
        );
    }

    @Test
    void load_会按压缩边界截断旧transcript并保留最近轮次() {
        appendMessage("user", "第一轮问题", "turn-1", Instant.parse("2026-03-23T10:00:00Z"));
        appendMessage("assistant", "第一轮回答", "turn-1", Instant.parse("2026-03-23T10:00:05Z"));
        String turn2UserEntryId = appendMessage("user", "第二轮问题", "turn-2", Instant.parse("2026-03-23T10:01:00Z"));
        appendToolCall("turn-2", "tool.beta", "call-b", Instant.parse("2026-03-23T10:01:01Z"));
        appendToolResult("turn-2", "tool.beta", "call-b", "{\"summary\":\"中间结果 beta\"}", Instant.parse("2026-03-23T10:01:03Z"));
        appendMessage("assistant", "第二轮回答", "turn-2", Instant.parse("2026-03-23T10:01:05Z"));
        appendMessage("user", "第三轮问题", "turn-3", Instant.parse("2026-03-23T10:02:00Z"));
        appendToolCall("turn-3", "tool.gamma", "call-c", Instant.parse("2026-03-23T10:02:01Z"));
        appendToolResult("turn-3", "tool.gamma", "call-c", "{\"summary\":\"最新结果 gamma\"}", Instant.parse("2026-03-23T10:02:03Z"));
        appendMessage("assistant", "第三轮回答", "turn-3", Instant.parse("2026-03-23T10:02:05Z"));

        appendToolCall("turn-1", "tool.alpha", "call-a", Instant.parse("2026-03-23T10:00:01Z"));
        appendToolResult("turn-1", "tool.alpha", "call-a", "{\"summary\":\"旧结果 alpha\"}", Instant.parse("2026-03-23T10:00:03Z"));

        transcriptRepository.appendEntry(
                SESSION_ID,
                TranscriptEntryType.COMPACTION_SUMMARY,
                null,
                null,
                "trace-compaction",
                false,
                false,
                Map.of(
                        "summary", "已压缩更早轮次，只保留近期关键事实",
                        "firstKeptEntryId", turn2UserEntryId,
                        "keyPoints", List.of("已确认约束", "后续继续推进最近轮次"),
                        "checkpoint", Map.of(
                                "goal", "整理上下文",
                                "currentPhase", "tool_result",
                                "completedItems", List.of("已完成更早轮次压缩"),
                                "resumePlan", List.of("继续结合最近原始 transcript 执行"),
                                "neededContextRefs", List.of("memory:doc-1")
                        )
                ),
                Instant.parse("2026-03-23T10:02:20Z")
        );

        artifactRepository.save(
                SESSION_ID,
                null,
                "trace-artifact-1",
                "report",
                "结果一",
                "最旧的产物",
                Map.of("seq", 1),
                "ACTIVE",
                Instant.parse("2026-03-23T10:00:20Z")
        );
        artifactRepository.save(
                SESSION_ID,
                null,
                "trace-artifact-2",
                "report",
                "结果二",
                "第二个产物",
                Map.of("seq", 2),
                "ACTIVE",
                Instant.parse("2026-03-23T10:01:20Z")
        );
        artifactRepository.save(
                SESSION_ID,
                null,
                "trace-artifact-3",
                "report",
                "结果三",
                "最新的产物",
                Map.of("seq", 3),
                "ACTIVE",
                Instant.parse("2026-03-23T10:02:20Z")
        );

        ContextEngine.ContextSnapshot snapshot = contextEngine.load(buildState(), 320);
        String history = serializeMessages(snapshot.historyMessages());

        // 工具名中的 . 被 sanitizeToolName 替换为 _（OpenAI API 名称模式要求）
        assertThat(history)
                .contains("任务检查点")
                .contains("历史压缩摘要")
                .contains("[user] 第二轮问题")
                .contains("[tool_call] tool_beta")
                .contains("tool_beta")
                .contains("[assistant] 第二轮回答")
                .contains("[user] 第三轮问题")
                .contains("[tool_call] tool_gamma")
                .contains("tool_gamma")
                .contains("[assistant] 第三轮回答")
                .doesNotContain("tool_alpha")
                .doesNotContain("第一轮问题")
                .doesNotContain("第一轮回答");
        assertThat(snapshot.pruningApplied()).isFalse();
        assertThat(snapshot.compactionApplied()).isTrue();
        assertThat(snapshot.artifactSection())
                .contains("结果三")
                .contains("结果二")
                .doesNotContain("结果一");
        assertThat(snapshot.debugPayload())
                .containsEntry("historyMessageCount", 10)
                .containsEntry("toolResultCount", 2)
                .containsEntry("toolResultTotalCount", 2)
                .containsEntry("artifactCount", 2)
                .containsEntry("checkpointApplied", true)
                .containsEntry("compactionFirstKeptEntryId", turn2UserEntryId);
    }

    @Test
    void recordReport_会保存工具结果Token与调试载荷() {
        appendMessage("user", "查询最近状态", "turn-1", Instant.parse("2026-03-23T11:00:00Z"));
        appendMessage("assistant", "已经整理好了", "turn-1", Instant.parse("2026-03-23T11:00:05Z"));
        appendToolResult("turn-1", "tool.status", "call-status", "{\"summary\":\"状态正常\"}", Instant.parse("2026-03-23T11:00:10Z"));
        artifactRepository.save(
                SESSION_ID,
                null,
                "trace-status",
                "status-card",
                "状态卡片",
                "同步生成一张状态卡片",
                Map.of("level", "ok"),
                "ACTIVE",
                Instant.parse("2026-03-23T11:00:11Z")
        );

        ContextEngine.ContextSnapshot snapshot = contextEngine.load(buildState(), 256);
        AssembledContext context = new AssembledContext(
                "system",
                List.of(),
                snapshot.historyMessages(),
                "user",
                List.of(),
                new TokenBudget(80, 90, 70, 20, 30, 10, 12, 34, 21, 7, 18),
                0,
                0.0f,
                21,
                false,
                List.of("exp-1"),
                null
        );

        contextEngine.recordReport(
                buildState(),
                snapshot,
                context,
                32768,
                4096
        );

        var row = contextReportRepository.findLatestBySessionId(SESSION_ID).orElseThrow();

        assertThat(row.traceId()).isEqualTo("trace-context");
        assertThat(row.toolResultTokens()).isEqualTo(18);
        assertThat(row.pruningApplied()).isEqualTo(snapshot.pruningApplied());
        assertThat(contextReportRepository.readPayload(row.id()))
                .containsKey("historyPreview")
                .containsEntry("workingMemoryTokens", 21);
    }

    private String appendMessage(String role, String content, String turnId, Instant createdAt) {
        return transcriptRepository.appendMessageEntry(
                SESSION_ID,
                role,
                content,
                null,
                "trace-context",
                null,
                null,
                null,
                null,
                createdAt
        );
    }

    private void appendToolCall(String turnId, String toolId, String callId, Instant createdAt) {
        transcriptRepository.appendEntry(
                SESSION_ID,
                TranscriptEntryType.TOOL_CALL,
                "tool",
                turnId,
                "trace-context",
                true,
                false,
                Map.of(
                        "toolId", toolId,
                        "callId", callId,
                        "inputJson", "{\"q\":\"context\"}"
                ),
                createdAt
        );
    }

    private void appendToolResult(String turnId, String toolId, String callId, String outputJson, Instant createdAt) {
        transcriptRepository.appendEntry(
                SESSION_ID,
                TranscriptEntryType.TOOL_RESULT,
                "tool",
                turnId,
                "trace-context",
                true,
                false,
                Map.of(
                        "toolId", toolId,
                        "callId", callId,
                        "success", true,
                        "outputJson", outputJson
                ),
                createdAt
        );
    }

    private String serializeMessages(List<Message> messages) {
        StringBuilder buffer = new StringBuilder();
        for (Message message : messages) {
            switch (message) {
                case UserMessage userMessage -> buffer.append("[user] ").append(userMessage.getText()).append('\n');
                case AssistantMessage assistantMessage -> {
                    if (assistantMessage.hasToolCalls()) {
                        assistantMessage.getToolCalls().forEach(toolCall -> buffer
                                .append("[tool_call] ")
                                .append(toolCall.name())
                                .append('\n'));
                    }
                    if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                        buffer.append("[assistant] ").append(assistantMessage.getText()).append('\n');
                    }
                }
                case ToolResponseMessage toolResponseMessage -> toolResponseMessage.getResponses().forEach(response ->
                        buffer.append("[tool_result] ")
                                .append(response.name())
                                .append(' ')
                                .append(response.responseData())
                                .append('\n'));
                default -> { }
            }
        }
        return buffer.toString();
    }

    private ReactAgentState buildState() {
        return ReactAgentState.builder()
                .traceId("trace-context")
                .sessionId(SESSION_ID)
                .goal("整理上下文")
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .parentTraceId(null)
                .depth(0)
                .preferredProvider(null)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .reasoningSummary(null)
                .allowedToolIds(null)
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();
    }

    private static final class CollectingMemoryEventBus implements MemoryEventBus {

        private final List<MemoryEvent> events = new ArrayList<>();

        @Override
        public void publish(MemoryEvent event) {
            events.add(event);
        }
    }
}
