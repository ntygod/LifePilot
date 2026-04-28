package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.memory.event.MemoryEvent;
import com.lifepilot.memory.event.MemoryEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transcript 条目仓储。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Repository
public class SessionTranscriptRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionTranscriptRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreRepository sessionStoreRepository;
    @Nullable
    private final MemoryEventBus memoryEventBus;

    public record SessionTranscriptEntryRow(
            String id,
            String sessionId,
            String branchId,
            String entryType,
            @Nullable String role,
            @Nullable String turnId,
            @Nullable String traceId,
            boolean visibleToModel,
            boolean visibleToUser,
            String payloadJson,
            int tokenEstimate,
            Instant createdAt
    ) {
    }

    public record TranscriptMessageViewRow(
            String entryId,
            String sessionId,
            String entryType,
            String role,
            String content,
            @Nullable String reasoningSummary,
            @Nullable String a2uiComponentsJson,
            @Nullable String reactStepsJson,
            @Nullable String completionMode,
            @Nullable String resumedFromTraceId,
            @Nullable String turnId,
            @Nullable String traceId,
            boolean visibleToModel,
            boolean visibleToUser,
            Instant createdAt,
            @Nullable String reasoningContent,
            @Nullable Long reasoningDurationMs
    ) {
        /**
         * 兼容旧测试构造器 — 不携带 reasoning_content / reasoning_duration_ms 时走 null。
         *
         * <p>用于现有 ChatSessionService_*测试 和其他 fixture 直接 new 出 row 的场景，
         * 避免一次给所有调用点改 15→17 字段。新构造点应直接用主构造器。
         */
        public TranscriptMessageViewRow(
                String entryId,
                String sessionId,
                String entryType,
                String role,
                String content,
                @Nullable String reasoningSummary,
                @Nullable String a2uiComponentsJson,
                @Nullable String reactStepsJson,
                @Nullable String completionMode,
                @Nullable String resumedFromTraceId,
                @Nullable String turnId,
                @Nullable String traceId,
                boolean visibleToModel,
                boolean visibleToUser,
                Instant createdAt
        ) {
            this(entryId, sessionId, entryType, role, content, reasoningSummary,
                    a2uiComponentsJson, reactStepsJson, completionMode, resumedFromTraceId,
                    turnId, traceId, visibleToModel, visibleToUser, createdAt, null, null);
        }
    }

    public SessionTranscriptRepository(JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper,
                                       SessionStoreRepository sessionStoreRepository) {
        this(jdbcTemplate, objectMapper, sessionStoreRepository, null);
    }

    @Autowired
    public SessionTranscriptRepository(JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper,
                                       SessionStoreRepository sessionStoreRepository,
                                       @Nullable MemoryEventBus memoryEventBus) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sessionStoreRepository = sessionStoreRepository;
        this.memoryEventBus = memoryEventBus;
    }

    public String appendMessageEntry(String sessionId,
                                     String role,
                                     String content,
                                     @Nullable String reasoningSummary,
                                     @Nullable String turnId,
                                     @Nullable String traceId,
                                     @Nullable String a2uiComponentsJson,
                                     @Nullable String reactStepsJson,
                                     @Nullable CompletionMode completionMode,
                                     @Nullable String resumedFromTraceId,
                                     Instant createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        if (reasoningSummary != null && !reasoningSummary.isBlank()) {
            payload.put("reasoningSummary", reasoningSummary);
        }
        if (a2uiComponentsJson != null && !a2uiComponentsJson.isBlank()) {
            payload.put("a2uiComponentsJson", a2uiComponentsJson);
        }
        if (reactStepsJson != null && !reactStepsJson.isBlank()) {
            payload.put("reactStepsJson", reactStepsJson);
        }
        if (completionMode != null) {
            payload.put("completionMode", completionMode.name());
        }
        if (resumedFromTraceId != null && !resumedFromTraceId.isBlank()) {
            payload.put("resumedFromTraceId", resumedFromTraceId);
        }
        return appendEntry(
                sessionId,
                TranscriptEntryType.fromLegacyRole(role),
                role,
                normalizeBlank(turnId),
                normalizeBlank(traceId),
                true,
                true,
                payload,
                createdAt
        );
    }

    public String appendMessageEntry(String sessionId,
                                     String role,
                                     String content,
                                     @Nullable String reasoningSummary,
                                     @Nullable String traceId,
                                     @Nullable String a2uiComponentsJson,
                                     @Nullable String reactStepsJson,
                                     @Nullable CompletionMode completionMode,
                                     @Nullable String resumedFromTraceId,
                                     Instant createdAt) {
        return appendMessageEntry(sessionId, role, content, reasoningSummary, null, traceId,
                a2uiComponentsJson, reactStepsJson, completionMode, resumedFromTraceId, createdAt);
    }

    public void updateVisibility(String entryId, boolean visibleToModel, boolean visibleToUser) {
        jdbcTemplate.update("""
                UPDATE session_transcript_entries
                SET visible_to_model = ?, visible_to_user = ?
                WHERE id = ?
                """,
                visibleToModel ? 1 : 0,
                visibleToUser ? 1 : 0,
                entryId
        );
    }

    public String appendEntry(String sessionId,
                              TranscriptEntryType entryType,
                              @Nullable String role,
                              @Nullable String turnId,
                              @Nullable String traceId,
                              boolean visibleToModel,
                              boolean visibleToUser,
                              Map<String, Object> payload,
                              Instant createdAt) {
        return appendEntryInternal(
                sessionId,
                entryType,
                role,
                turnId,
                traceId,
                visibleToModel,
                visibleToUser,
                payload,
                createdAt,
                true
        );
    }

    private String appendEntryInternal(String sessionId,
                                       TranscriptEntryType entryType,
                                       @Nullable String role,
                                       @Nullable String turnId,
                                       @Nullable String traceId,
                                       boolean visibleToModel,
                                       boolean visibleToUser,
                                       Map<String, Object> payload,
                                       Instant createdAt,
                                       boolean publishEvents) {
        sessionStoreRepository.ensureSessionShell(sessionId);
        String id = UUID.randomUUID().toString();
        String payloadJson = serializePayload(payload);
        jdbcTemplate.update("""
                INSERT INTO session_transcript_entries (
                    id, session_id, parent_id, branch_id, entry_type, role,
                    turn_id, trace_id, visible_to_model,
                    visible_to_user, payload_json, token_estimate, created_at
                ) VALUES (?, ?, NULL, 'main', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                sessionId,
                entryType.value(),
                role,
                turnId,
                traceId,
                visibleToModel ? 1 : 0,
                visibleToUser ? 1 : 0,
                payloadJson,
                estimatePayloadTokens(payload),
                createdAt.toString()
        );
        if (publishEvents) {
            publishEvents(
                    id,
                    sessionId,
                    "main",
                    entryType,
                    role,
                    turnId,
                    traceId,
                    visibleToModel,
                    visibleToUser,
                    payload,
                    createdAt
            );
        }
        return id;
    }

    public List<SessionTranscriptEntryRow> findBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                SELECT id, session_id, branch_id, entry_type, role, turn_id, trace_id,
                       visible_to_model, visible_to_user, payload_json,
                       token_estimate, created_at
                FROM session_transcript_entries
                WHERE session_id = ?
                ORDER BY created_at ASC, rowid ASC
                """,
                this::mapRow,
                sessionId
        );
    }

    public List<TranscriptMessageViewRow> findUserConversationRowsBySessionId(String sessionId) {
        return findBySessionId(sessionId).stream()
                .filter(SessionTranscriptEntryRow::visibleToUser)
                .map(this::toTranscriptMessageView)
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<SessionTranscriptEntryRow> findById(String entryId) {
        List<SessionTranscriptEntryRow> rows = jdbcTemplate.query("""
                SELECT id, session_id, branch_id, entry_type, role, turn_id, trace_id,
                       visible_to_model, visible_to_user, payload_json,
                       token_estimate, created_at
                FROM session_transcript_entries
                WHERE id = ?
                """,
                this::mapRow,
                entryId
        );
        return rows.stream().findFirst();
    }

    public String copyEntry(String targetSessionId, SessionTranscriptEntryRow sourceRow) {
        return appendEntryInternal(
                targetSessionId,
                TranscriptEntryType.fromValue(sourceRow.entryType()),
                sourceRow.role(),
                sourceRow.turnId(),
                sourceRow.traceId(),
                sourceRow.visibleToModel(),
                sourceRow.visibleToUser(),
                deserializePayload(sourceRow.payloadJson()),
                sourceRow.createdAt(),
                false
        );
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM session_transcript_entries WHERE session_id = ?", sessionId);
    }

    private SessionTranscriptEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionTranscriptEntryRow(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("branch_id"),
                rs.getString("entry_type"),
                rs.getString("role"),
                rs.getString("turn_id"),
                rs.getString("trace_id"),
                rs.getInt("visible_to_model") == 1,
                rs.getInt("visible_to_user") == 1,
                rs.getString("payload_json"),
                rs.getInt("token_estimate"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("transcript payload 序列化失败，写入空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    private void publishEvents(String entryId,
                               String sessionId,
                               String branchId,
                               TranscriptEntryType entryType,
                               @Nullable String role,
                               @Nullable String turnId,
                               @Nullable String traceId,
                               boolean visibleToModel,
                               boolean visibleToUser,
                               Map<String, Object> payload,
                               Instant createdAt) {
        publishEvent(new MemoryEvent.TranscriptEntryCommitted(
                sessionId,
                entryId,
                entryType.value(),
                role,
                turnId,
                traceId,
                visibleToModel,
                visibleToUser,
                createdAt
        ));

        switch (entryType) {
            case ASSISTANT_MESSAGE -> {
                String content = stringValue(payload.get("content"));
                if (content == null) {
                    return;
                }
                String reasoningSummary = stringValue(payload.get("reasoningSummary"));
                publishEvent(new MemoryEvent.AssistantReplyCommitted(
                        sessionId,
                        turnId,
                        traceId,
                        entryId,
                        content,
                        reasoningSummary,
                        createdAt
                ));
                publishEvent(new MemoryEvent.TurnCommitted(
                        sessionId,
                        turnId,
                        traceId,
                        null,
                        entryId,
                        content,
                        createdAt
                ));
            }
            case TOOL_CALL -> {
                String toolId = stringValue(payload.get("toolId"));
                if (toolId == null) {
                    return;
                }
                publishEvent(new MemoryEvent.ToolCallCommitted(
                        sessionId,
                        turnId,
                        traceId,
                        entryId,
                        toolId,
                        stringValue(payload.get("callId")),
                        stringValue(payload.get("inputJson")),
                        createdAt
                ));
            }
            case TOOL_RESULT -> {
                String toolId = stringValue(payload.get("toolId"));
                if (toolId == null) {
                    return;
                }
                publishEvent(new MemoryEvent.ToolResultCommitted(
                        sessionId,
                        turnId,
                        traceId,
                        entryId,
                        toolId,
                        stringValue(payload.get("callId")),
                        booleanValue(payload.get("success")),
                        stringValue(payload.get("outputJson")),
                        stringValue(payload.get("artifactId")),
                        createdAt
                ));
            }
            case ARTIFACT_REF -> {
                String artifactId = stringValue(payload.get("artifactId"));
                if (artifactId == null) {
                    return;
                }
                publishEvent(new MemoryEvent.ArtifactCommitted(
                        sessionId,
                        artifactId,
                        entryId,
                        traceId,
                        stringValue(payload.get("artifactType")) != null
                                ? stringValue(payload.get("artifactType"))
                                : "artifact",
                        stringValue(payload.get("title")),
                        createdAt
                ));
            }
            case COMPACTION_SUMMARY -> publishEvent(new MemoryEvent.CompactionCommitted(
                    sessionId,
                    branchId,
                    entryId,
                    stringValue(payload.get("firstKeptEntryId")),
                    createdAt
            ));
            case MEMORY_FLUSH_EVENT -> publishEvent(new MemoryEvent.PreCompactionMemoryFlushCommitted(
                    sessionId,
                    branchId,
                    stringListValue(payload.get("documentIds")),
                    createdAt
            ));
            default -> {
                // 其他类型暂不发布更细粒度事件
            }
        }
    }

    @Nullable
    private TranscriptMessageViewRow toTranscriptMessageView(SessionTranscriptEntryRow row) {
        if (row.role() == null || row.role().isBlank()) {
            return null;
        }
        Map<String, Object> payload = deserializePayload(row.payloadJson());
        String content = stringValue(payload.get("content"));
        if (content == null || content.isBlank()) {
            return null;
        }
        return new TranscriptMessageViewRow(
                row.id(),
                row.sessionId(),
                row.entryType(),
                row.role(),
                content,
                stringValue(payload.get("reasoningSummary")),
                stringValue(payload.get("a2uiComponentsJson")),
                stringValue(payload.get("reactStepsJson")),
                stringValue(payload.get("completionMode")),
                stringValue(payload.get("resumedFromTraceId")),
                row.turnId(),
                row.traceId(),
                row.visibleToModel(),
                row.visibleToUser(),
                row.createdAt(),
                // ChatTurnService.buildAssistantPayload 写 snake_case "reasoning_content"
                // 与 LLM 协议字段名对齐，反序列化时按同一 key 读出
                stringValue(payload.get("reasoning_content")),
                longValue(payload.get("reasoning_duration_ms"))
        );
    }

    @Nullable
    private Long longValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            String text = value.toString();
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> deserializePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() { });
            return payload != null ? payload : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("transcript payload 反序列化失败，返回空对象: error={}", e.getMessage());
            return Map.of();
        }
    }

    @Nullable
    private String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private boolean booleanValue(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private List<String> stringListValue(@Nullable Object value) {
        if (value instanceof List<?> items) {
            return items.stream()
                    .map(Object::toString)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private void publishEvent(MemoryEvent event) {
        if (memoryEventBus == null || event == null) {
            return;
        }
        try {
            memoryEventBus.publish(event);
        } catch (Exception e) {
            log.warn("发布记忆域事件失败: type={}, sessionId={}, error={}",
                    event.eventType(), event.sessionId(), e.getMessage());
        }
    }

    private int estimatePayloadTokens(Map<String, Object> payload) {
        StringBuilder buffer = new StringBuilder();
        Object content = payload.get("content");
        if (content != null) {
            buffer.append(content);
        }
        Object reasoningSummary = payload.get("reasoningSummary");
        if (reasoningSummary != null) {
            buffer.append('\n').append(reasoningSummary);
        }
        Object inputJson = payload.get("inputJson");
        if (inputJson != null) {
            buffer.append('\n').append(inputJson);
        }
        Object outputJson = payload.get("outputJson");
        if (outputJson != null) {
            buffer.append('\n').append(outputJson);
        }
        Object summary = payload.get("summary");
        if (summary != null) {
            buffer.append('\n').append(summary);
        }
        Object title = payload.get("title");
        if (title != null) {
            buffer.append('\n').append(title);
        }
        int length = buffer.length();
        if (length == 0) {
            return 0;
        }
        long cjkChars = buffer.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = length - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
