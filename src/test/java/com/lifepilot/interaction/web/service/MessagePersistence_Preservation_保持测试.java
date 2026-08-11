package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.learning.extraction.MemoryExtractionCandidateRepository;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.JdbcTranscriptStore;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.InvalidationKind;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.SourceType;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.events.SourceInvalidated;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private SessionArtifactRepository artifactRepository;
    private JdbcTranscriptStore transcriptStore;
    private ChatSessionService chatSessionService;
    private MemoryExtractionCandidateRepository memoryExtractionCandidateRepository;
    private InjectionRecordRepository injectionRecordRepository;
    private ApplicationEventPublisher eventPublisher;
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
                CREATE TABLE message_attachments (
                    id TEXT PRIMARY KEY,
                    entry_id TEXT,
                    session_id TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    mime_type TEXT NOT NULL,
                    url TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
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
                CREATE TABLE memory_extraction_candidates (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    turn_id TEXT,
                    source_entry_id TEXT,
                    target_space_id TEXT,
                    operation TEXT NOT NULL,
                    entity_name TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    decision_json TEXT NOT NULL,
                    candidate_status TEXT NOT NULL DEFAULT 'VALIDATED',
                    validation_status TEXT NOT NULL DEFAULT 'VALIDATED',
                    rejection_reason TEXT,
                    persisted_entity_id TEXT,
                    base_entity_id TEXT,
                    error_message TEXT,
                    evidence_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                    trust_level TEXT NOT NULL DEFAULT 'UNVERIFIED',
                    trust_score REAL NOT NULL DEFAULT 0.0,
                    evidence_excerpt TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_extraction_turn_status (
                    turn_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    reason TEXT,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE memory_injection_records (
                    id TEXT PRIMARY KEY,
                    source_entry_id TEXT,
                    session_id TEXT NOT NULL,
                    entity_ids_json TEXT NOT NULL,
                    entity_type TEXT NOT NULL DEFAULT 'GENERAL',
                    source_trace_id TEXT,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (source_entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE
                )
                """);

        var objectMapper = new ObjectMapper();
        sessionStoreRepository = new SessionStoreRepository(jdbcTemplate, objectMapper);
        transcriptRepository = new SessionTranscriptRepository(jdbcTemplate, objectMapper, sessionStoreRepository, null);
        artifactRepository = new SessionArtifactRepository(jdbcTemplate, objectMapper, sessionStoreRepository);
        memoryExtractionCandidateRepository = new MemoryExtractionCandidateRepository(jdbcTemplate, objectMapper);
        injectionRecordRepository = new InjectionRecordRepository(jdbcTemplate, objectMapper);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sessionRepository = new ChatSessionRepository(sessionStoreRepository);
        transcriptStore = new JdbcTranscriptStore(sessionStoreRepository, transcriptRepository, sessionRepository);
        chatSessionService = new ChatSessionService(
                sessionRepository,
                new SessionKnowledgeBaseRepository(jdbcTemplate),
                new AttachmentRepository(jdbcTemplate),
                objectMapper,
                transcriptRepository,
                sessionStoreRepository,
                null,
                null,
                null,
                null,
                memoryExtractionCandidateRepository,
                injectionRecordRepository,
                null,
                null,
                null,
                null,
                artifactRepository,
                eventPublisher
        );
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
    void appendAssistantMessage_保留任务恢复摘要字段() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "恢复摘要会话");
        sessionRepository.save(session);

        String taskRecoveryJson = """
                {"status":"SUSPENDED","title":"等待你补充信息","detail":"你直接回复补充内容，知微会接着当前进度继续。","canResume":false,"canRestart":true,"resumeMode":"user_reply"}
                """;

        transcriptStore.appendAssistantMessage(
                session.id(),
                null,
                "我需要你补充仓库地址后再继续。",
                "等待用户补充信息",
                "trace-recovery-1",
                null,
                "[{\"type\":\"SUSPEND\",\"reason\":\"等待用户补充\"}]",
                taskRecoveryJson,
                CompletionMode.SUSPENDED,
                null,
                null
        );

        var messages = transcriptRepository.findUserConversationRowsBySessionId(session.id());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().taskRecoveryJson()).contains("等待你补充信息");
        assertThat(messages.getFirst().taskRecoveryJson()).contains("\"canResume\":false");

        var refreshedMessages = chatSessionService.getSessionMessages(session.id());
        assertThat(refreshedMessages).hasSize(1);
        assertThat(refreshedMessages.getFirst().taskRecovery())
                .containsEntry("title", "等待你补充信息")
                .containsEntry("canResume", false)
                .containsEntry("resumeMode", "user_reply");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendAssistantMessage_保留工具执行摘要字段() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "工具摘要会话");
        sessionRepository.save(session);

        String toolsSummaryJson = """
                [
                  {
                    "toolId":"shell.exec",
                    "toolName":"Shell 执行",
                    "executionKind":"TOOL",
                    "status":"FAILED",
                    "success":false,
                    "latencyMs":35,
                    "inputSummary":"执行 `npm test`",
                    "outputSummary":"测试失败",
                    "failureCategory":"COMMAND",
                    "recoveryHint":"命令或代码没有完成，可以修正错误后继续执行。",
                    "recoveryActions":[{"id":"resume","label":"修正后继续","mode":"resume","category":"COMMAND"}]
                  }
                ]
                """;

        transcriptStore.appendAssistantMessage(
                session.id(),
                null,
                "测试失败，我可以从失败处继续。",
                "工具执行失败",
                "trace-tools-1",
                null,
                "[{\"type\":\"TOOL_CALL\",\"toolId\":\"shell.exec\"}]",
                toolsSummaryJson,
                null,
                CompletionMode.DEGRADED,
                null,
                null
        );

        var messages = transcriptRepository.findUserConversationRowsBySessionId(session.id());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().toolsSummaryJson()).contains("Shell 执行");
        assertThat(messages.getFirst().toolsSummaryJson()).contains("修正后继续");

        var refreshedMessages = chatSessionService.getSessionMessages(session.id());
        assertThat(refreshedMessages).hasSize(1);
        var summaries = refreshedMessages.getFirst().toolsSummary();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst())
                .containsEntry("toolId", "shell.exec")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("recoveryHint", "命令或代码没有完成，可以修正错误后继续执行。");
        assertThat((java.util.List<java.util.Map<String, Object>>) summaries.getFirst().get("recoveryActions"))
                .extracting(action -> action.get("label"))
                .containsExactly("修正后继续");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendAssistantMessage_保留执行约束摘要字段() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "执行约束会话");
        sessionRepository.save(session);

        String executionConstraintsJson = """
                {
                  "disabledTools":[
                    {"id":"web","label":"联网搜索","reason":"按本轮用户要求禁用"},
                    {"id":"browser","label":"浏览器操作","reason":"按本轮用户要求禁用"}
                  ]
                }
                """;

        transcriptStore.appendAssistantMessage(
                session.id(),
                null,
                "已按本地资料整理，未联网。",
                "遵守本轮不联网约束",
                "trace-constraints-1",
                null,
                null,
                null,
                null,
                executionConstraintsJson,
                CompletionMode.NORMAL,
                null,
                null
        );

        var messages = transcriptRepository.findUserConversationRowsBySessionId(session.id());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().executionConstraintsJson()).contains("联网搜索");
        assertThat(messages.getFirst().executionConstraintsJson()).contains("浏览器操作");

        var refreshedMessages = chatSessionService.getSessionMessages(session.id());
        assertThat(refreshedMessages).hasSize(1);
        var executionConstraints = refreshedMessages.getFirst().executionConstraints();
        assertThat(executionConstraints).isNotNull();
        var disabledTools = (List<Map<String, Object>>) executionConstraints.get("disabledTools");
        assertThat(disabledTools)
                .extracting(item -> item.get("id"))
                .containsExactly("web", "browser");
    }

    @Test
    void 历史消息接口_带出当前恢复尝试上下文() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "恢复上下文会话");
        sessionRepository.save(session);
        String turnId = "turn-resume-1";
        String assistantEntryId = transcriptStore.appendAssistantMessage(
                session.id(),
                turnId,
                "我已经接着上次失败的测试继续处理。",
                "恢复执行完成",
                "trace-resumed-1",
                null,
                "[{\"type\":\"ANSWER\",\"content\":\"done\"}]",
                CompletionMode.NORMAL,
                "trace-failed-1",
                null
        );
        String requestPayloadJson = """
                {
                  "content": "运行前端测试并修复失败",
                  "attachmentIds": null,
                  "preferredProvider": null,
                  "singleTurnOverride": null,
                  "lastRecoveryContext": {
                    "action": "RESUME",
                    "resumeInput": "继续，先修 npm test",
                    "sourceTraceId": "trace-failed-1",
                    "assistantEntryId": "assistant-before-resume",
                    "title": "Shell 执行 没有完成",
                    "checkpoint": {
                      "kind": "TOOL_FAILURE",
                      "toolId": "shell.exec",
                      "toolName": "Shell 执行",
                      "inputSummary": "执行 `npm test`"
                    },
                    "nextActions": ["从失败命令后继续执行验证"]
                  }
                }
                """;
        var chatTurnService = mock(ChatTurnService.class);
        when(chatTurnService.findBySessionId(session.id())).thenReturn(java.util.List.of(new ChatTurnRecord(
                turnId,
                session.id(),
                ChatTurnAction.RESUME,
                ChatTurnStatus.SUCCESS,
                requestPayloadJson,
                "user-entry-1",
                assistantEntryId,
                "trace-resumed-1",
                "trace-failed-1",
                CompletionMode.NORMAL.name(),
                null,
                null,
                2,
                Instant.now(),
                Instant.now()
        )));
        var attachmentRepository = mock(AttachmentRepository.class);
        when(attachmentRepository.findByEntryIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.Map.of());
        var serviceWithTurns = new ChatSessionService(
                sessionRepository,
                mock(SessionKnowledgeBaseRepository.class),
                attachmentRepository,
                new ObjectMapper(),
                transcriptRepository,
                sessionStoreRepository,
                null,
                null,
                null,
                chatTurnService,
                memoryExtractionCandidateRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        var messages = serviceWithTurns.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().turnRecoveryContext())
                .containsEntry("action", "RESUME")
                .containsEntry("resumeInput", "继续，先修 npm test")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("title", "Shell 执行 没有完成");
        assertThat((java.util.Map<String, Object>) messages.getFirst().turnRecoveryContext().get("checkpoint"))
                .containsEntry("toolId", "shell.exec")
                .containsEntry("inputSummary", "执行 `npm test`");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 历史消息接口_带出重启恢复尝试上下文() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "重启恢复上下文会话");
        sessionRepository.save(session);
        String turnId = "turn-restart-1";
        String assistantEntryId = transcriptStore.appendAssistantMessage(
                session.id(),
                turnId,
                "我已经重新开始并修复失败测试。",
                "重启执行完成",
                "trace-restart-1",
                null,
                "[{\"type\":\"ANSWER\",\"content\":\"done\"}]",
                CompletionMode.NORMAL,
                null,
                null
        );
        String requestPayloadJson = """
                {
                  "content": "运行前端测试并修复失败",
                  "attachmentIds": null,
                  "preferredProvider": null,
                  "singleTurnOverride": null,
                  "lastRecoveryContext": {
                    "action": "RESTART",
                    "resumeInput": "重新开始：重新开始。目标：Shell 执行（命令执行）。",
                    "sourceTraceId": "trace-failed-1",
                    "assistantEntryId": "assistant-before-restart",
                    "title": "Shell 执行 没有完成",
                    "checkpoint": {
                      "kind": "TOOL_FAILURE",
                      "toolId": "shell.exec",
                      "toolName": "Shell 执行",
                      "inputSummary": "执行 `npm test`"
                    },
                    "nextActions": ["重新执行失败命令并验证"]
                  }
                }
                """;
        var chatTurnService = mock(ChatTurnService.class);
        when(chatTurnService.findBySessionId(session.id())).thenReturn(java.util.List.of(new ChatTurnRecord(
                turnId,
                session.id(),
                ChatTurnAction.RESTART,
                ChatTurnStatus.SUCCESS,
                requestPayloadJson,
                "user-entry-1",
                assistantEntryId,
                "trace-restart-1",
                null,
                CompletionMode.NORMAL.name(),
                null,
                null,
                2,
                Instant.now(),
                Instant.now()
        )));
        var attachmentRepository = mock(AttachmentRepository.class);
        when(attachmentRepository.findByEntryIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.Map.of());
        var serviceWithTurns = new ChatSessionService(
                sessionRepository,
                mock(SessionKnowledgeBaseRepository.class),
                attachmentRepository,
                new ObjectMapper(),
                transcriptRepository,
                sessionStoreRepository,
                null,
                null,
                null,
                chatTurnService,
                memoryExtractionCandidateRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        var messages = serviceWithTurns.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().turnRecoveryContext())
                .containsEntry("action", "RESTART")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("title", "Shell 执行 没有完成");
        assertThat((java.util.Map<String, Object>) messages.getFirst().turnRecoveryContext().get("checkpoint"))
                .containsEntry("toolId", "shell.exec")
                .containsEntry("inputSummary", "执行 `npm test`");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 历史消息接口_保留本轮已沉淀记忆() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "记忆沉淀会话");
        sessionRepository.save(session);
        String turnId = "turn-memory-1";
        transcriptStore.appendAssistantMessage(
                session.id(),
                turnId,
                "我记住了你更偏好轻量主界面。",
                "沉淀用户偏好",
                "trace-memory-1",
                null,
                "[{\"type\":\"ANSWER\",\"content\":\"done\"}]",
                CompletionMode.NORMAL,
                null,
                null
        );
        var now = Instant.parse("2026-07-04T02:20:00Z");
        jdbcTemplate.update("""
                INSERT INTO memory_extraction_candidates(
                    id, session_id, turn_id, source_entry_id, target_space_id,
                    operation, entity_name, entity_type, decision_json,
                    candidate_status, validation_status, persisted_entity_id,
                    evidence_kind, trust_level, trust_score, evidence_excerpt,
                    created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "candidate-1",
                session.id(),
                turnId,
                "entry-1",
                "default",
                "ADD",
                "偏好轻量主界面",
                "PREFERENCE",
                """
                {"description":"用户偏好更轻量的主界面","importanceScore":0.82,"temporality":"PERSISTENT"}
                """,
                "APPLIED",
                "VALIDATED",
                "entity-preference-1",
                "USER_EXPLICIT",
                "VERIFIED",
                0.91,
                "主界面做轻，做好交互",
                now.toString(),
                now.toString());

        var messages = chatSessionService.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().memoryChanges()).hasSize(1);
        var change = messages.getFirst().memoryChanges().getFirst();
        assertThat(change)
                .containsEntry("type", "memory")
                .containsEntry("id", "entity-preference-1")
                .containsEntry("name", "偏好轻量主界面");
        assertThat((java.util.Map<String, Object>) change.get("extra"))
                .containsEntry("operationLabel", "新增")
                .containsEntry("entityTypeLabel", "偏好")
                .containsEntry("description", "用户偏好更轻量的主界面")
                .containsEntry("importanceScore", 0.82f)
                .containsEntry("temporality", "PERSISTENT")
                .containsEntry("evidenceExcerpt", "主界面做轻，做好交互");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 历史消息接口_项目会话只回放项目内本轮记忆沉淀() {
        ChatSession session = ChatSession.create("项目记忆沉淀会话", "project-1");
        sessionRepository.save(session);
        String turnId = "turn-project-memory-1";
        transcriptStore.appendAssistantMessage(
                session.id(),
                turnId,
                "我记下项目里的主界面偏好。",
                "沉淀项目偏好",
                "trace-project-memory-1",
                null,
                "[{\"type\":\"ANSWER\",\"content\":\"done\"}]",
                CompletionMode.NORMAL,
                null,
                null
        );
        var now = Instant.parse("2026-07-04T02:30:00Z");
        jdbcTemplate.update("""
                INSERT INTO memory_extraction_candidates(
                    id, session_id, turn_id, source_entry_id, target_space_id,
                    operation, entity_name, entity_type, decision_json,
                    candidate_status, validation_status, persisted_entity_id,
                    evidence_kind, trust_level, trust_score, evidence_excerpt,
                    created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "candidate-project",
                session.id(),
                turnId,
                "entry-project",
                "space-project",
                "ADD",
                "项目主界面偏好",
                "PREFERENCE",
                """
                {"description":"项目里希望主界面保持轻量","importanceScore":0.83,"temporality":"PERSISTENT"}
                """,
                "APPLIED",
                "VALIDATED",
                "entity-project-preference",
                "USER_EXPLICIT",
                "EXPLICIT",
                0.93,
                "项目里主界面做轻",
                now.toString(),
                now.toString());
        jdbcTemplate.update("""
                INSERT INTO memory_extraction_candidates(
                    id, session_id, turn_id, source_entry_id, target_space_id,
                    operation, entity_name, entity_type, decision_json,
                    candidate_status, validation_status, persisted_entity_id,
                    evidence_kind, trust_level, trust_score, evidence_excerpt,
                    created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "candidate-personal",
                session.id(),
                turnId,
                "entry-personal",
                "space-personal",
                "ADD",
                "个人主界面偏好",
                "PREFERENCE",
                """
                {"description":"个人偏好主界面保持轻量","importanceScore":0.82,"temporality":"PERSISTENT"}
                """,
                "APPLIED",
                "VALIDATED",
                "entity-personal-preference",
                "USER_EXPLICIT",
                "EXPLICIT",
                0.91,
                "个人主界面做轻",
                now.plusSeconds(1).toString(),
                now.plusSeconds(1).toString());
        var projectContextResolver = mock(ProjectContextResolver.class);
        when(projectContextResolver.resolve("project-1"))
                .thenReturn(new ProjectContext("project-1", "space-project", "space-personal", "space-experience", true));
        var service = new ChatSessionService(
                sessionRepository,
                new SessionKnowledgeBaseRepository(jdbcTemplate),
                new AttachmentRepository(jdbcTemplate),
                new ObjectMapper(),
                transcriptRepository,
                sessionStoreRepository,
                null,
                null,
                null,
                null,
                memoryExtractionCandidateRepository,
                null,
                null,
                null,
                projectContextResolver,
                null,
                null,
                null
        );

        var messages = service.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().memoryChanges()).hasSize(1);
        var change = messages.getFirst().memoryChanges().getFirst();
        assertThat(change)
                .containsEntry("id", "entity-project-preference")
                .containsEntry("name", "项目主界面偏好");
        assertThat((Map<String, Object>) change.get("extra"))
                .containsEntry("spaceId", "space-project")
                .containsEntry("sourceKindLabel", "项目上下文")
                .containsEntry("evidenceExcerpt", "项目里主界面做轻");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 历史消息接口_回放本轮参考来源() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "来源回放会话");
        sessionRepository.save(session);
        String assistantEntryId = transcriptStore.appendAssistantMessage(
                session.id(),
                "turn-source-1",
                "我结合资料库和你的偏好给出了建议。",
                "参考资料与记忆",
                "trace-source-1",
                null,
                "[{\"type\":\"ANSWER\",\"content\":\"done\"}]",
                CompletionMode.NORMAL,
                null,
                null
        );
        var now = Instant.parse("2026-07-04T03:00:00Z");
        var sessionKbRepository = mock(SessionKnowledgeBaseRepository.class);
        when(sessionKbRepository.findKnowledgeBaseIdsBySessionId(session.id())).thenReturn(List.of("kb-product"));
        var knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        when(knowledgeBaseRepository.findById("kb-product")).thenReturn(Optional.of(new KnowledgeBase(
                "kb-product",
                "产品资料库",
                "产品定位与路线资料",
                null,
                null,
                "smart",
                Map.of(),
                0,
                0,
                List.of(),
                now,
                now
        )));
        var injectionRecordRepository = mock(InjectionRecordRepository.class);
        when(injectionRecordRepository.findEntityIdsBySourceEntryId(assistantEntryId))
                .thenReturn(List.of("entity-preference-1"));
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findById("entity-preference-1")).thenReturn(Optional.of(new TemporalEntity(
                "entity-preference-1",
                EntityType.PREFERENCE,
                "偏好轻量主界面",
                "用户希望本地个人 APP 主界面轻、交互自然",
                Map.of(),
                1,
                true,
                now,
                null,
                session.id(),
                0.96f,
                0.8f,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                MemoryEvidenceKind.USER_EXPLICIT,
                MemoryTrustLevel.VERIFIED,
                0.91f,
                2,
                now
        )));
        var attachmentRepository = mock(AttachmentRepository.class);
        when(attachmentRepository.findByEntryIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.Map.of());
        var service = new ChatSessionService(
                sessionRepository,
                sessionKbRepository,
                attachmentRepository,
                new ObjectMapper(),
                transcriptRepository,
                sessionStoreRepository,
                null,
                null,
                null,
                null,
                memoryExtractionCandidateRepository,
                injectionRecordRepository,
                semanticMemory,
                knowledgeBaseRepository,
                null,
                null,
                null,
                null
        );

        var messages = service.getSessionMessages(session.id());

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().sources()).hasSize(2);
        assertThat(messages.getFirst().sources())
                .anySatisfy(source -> assertThat(source)
                        .containsEntry("type", "knowledgeBase")
                        .containsEntry("id", "kb-product")
                        .containsEntry("name", "产品资料库"))
                .anySatisfy(source -> {
                    assertThat(source)
                            .containsEntry("type", "memory")
                            .containsEntry("id", "entity-preference-1")
                            .containsEntry("name", "偏好轻量主界面");
                    assertThat((Map<String, Object>) source.get("extra"))
                            .containsEntry("sourceKind", "INJECTED")
                            .containsEntry("sourceKindLabel", "本轮实际参考")
                            .containsEntry("usageReason", "这条回答参考了「偏好轻量主界面」这条偏好：用户希望本地个人 APP 主界面轻、交互自然。")
                            .containsEntry("usageImpact", "会影响语气、方案取舍和界面建议；长期生效；优先级较高；有 2 条证据支撑")
                            .containsEntry("entityTypeLabel", "偏好")
                            .containsEntry("description", "用户希望本地个人 APP 主界面轻、交互自然")
                            .containsEntry("sourceConversationId", session.id())
                            .containsEntry("extractionConfidence", 0.96f)
                            .containsEntry("importanceScore", 0.8f)
                            .containsEntry("temporality", "PERSISTENT")
                            .containsEntry("evidenceKind", "USER_EXPLICIT")
                            .containsEntry("trustLevel", "VERIFIED")
                            .containsEntry("evidenceCount", 2)
                            .containsEntry("createdAt", "2026-07-04T03:00:00Z")
                            .containsEntry("updatedAt", "2026-07-04T03:00:00Z");
                });
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

    @Test
    void 删除会话时应发布Session来源失效事件() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "删除来源失效");
        sessionRepository.save(session);
        transcriptStore.appendTurn(session.id(), "第一条", "第二条", null, "trace-delete-service");

        chatSessionService.deleteSession(session.id());

        assertThat(transcriptRepository.findBySessionId(session.id())).isEmpty();
        assertThat(sessionStoreRepository.findBySessionId(session.id())).isEmpty();
        verify(eventPublisher).publishEvent(
                new SourceInvalidated(SourceType.SESSION, session.id(), InvalidationKind.DELETED));
    }

    @Test
    void 清空会话消息时应同时删除会话产物和辅助记录() {
        ChatSession session = ChatSession.createWithId(UUID.randomUUID().toString(), "清空产物");
        sessionRepository.save(session);

        transcriptStore.appendTurn(session.id(), "生成报告", "报告已生成", null, "trace-artifact-clear");
        String assistantEntryId = transcriptRepository.findUserConversationRowsBySessionId(session.id()).stream()
                .filter(row -> "assistant".equals(row.role()))
                .findFirst()
                .orElseThrow()
                .entryId();
        artifactRepository.save(
                session.id(),
                null,
                "trace-artifact-clear",
                "file",
                "report.md",
                "测试报告",
                Map.of(
                        "kind", "FILE",
                        "fileName", "report.md",
                        "mimeType", "text/markdown",
                        "size", 128L
                ),
                "ACTIVE",
                null
        );
        injectionRecordRepository.save(
                assistantEntryId,
                session.id(),
                "trace-artifact-clear",
                List.of("entity-used-1")
        );
        injectionRecordRepository.saveWithType(
                "trace-experience-clear",
                session.id(),
                List.of("experience-1"),
                "EXPERIENCE"
        );
        jdbcTemplate.update("""
                INSERT INTO memory_extraction_candidates(
                    id, session_id, turn_id, source_entry_id, target_space_id,
                    operation, entity_name, entity_type, decision_json,
                    candidate_status, validation_status,
                    evidence_kind, trust_level, trust_score,
                    created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "candidate-clear-1",
                session.id(),
                "turn-clear-1",
                assistantEntryId,
                null,
                "ADD",
                "报告偏好",
                "PREFERENCE",
                "{}",
                "APPLIED",
                "VALIDATED",
                "USER_EXPLICIT",
                "VERIFIED",
                0.9,
                "2026-07-07T10:00:00Z",
                "2026-07-07T10:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO memory_extraction_turn_status(
                    turn_id, session_id, status, reason, started_at, completed_at, updated_at
                ) VALUES(?,?,?,?,?,?,?)
                """,
                "turn-clear-1",
                session.id(),
                "COMPLETED",
                null,
                "2026-07-07T10:00:00Z",
                "2026-07-07T10:00:01Z",
                "2026-07-07T10:00:01Z");
        assertThat(transcriptRepository.findBySessionId(session.id())).hasSize(2);
        assertThat(artifactRepository.findBySessionId(session.id())).hasSize(1);
        assertThat(countRowsBySession("memory_injection_records", session.id())).isEqualTo(2);
        assertThat(countRowsBySession("memory_extraction_candidates", session.id())).isEqualTo(1);
        assertThat(countRowsBySession("memory_extraction_turn_status", session.id())).isEqualTo(1);

        chatSessionService.clearSessionMessages(session.id());

        assertThat(transcriptRepository.findBySessionId(session.id())).isEmpty();
        assertThat(artifactRepository.findBySessionId(session.id())).isEmpty();
        assertThat(countRowsBySession("memory_injection_records", session.id())).isZero();
        assertThat(countRowsBySession("memory_extraction_candidates", session.id())).isZero();
        assertThat(countRowsBySession("memory_extraction_turn_status", session.id())).isZero();
        verify(eventPublisher).publishEvent(
                new SourceInvalidated(SourceType.SESSION, session.id(), InvalidationKind.CONTENT_CHANGED));
    }

    private int countRowsBySession(String tableName, String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE session_id = ?",
                Integer.class,
                sessionId);
    }
}
