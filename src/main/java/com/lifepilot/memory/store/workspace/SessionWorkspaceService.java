package com.lifepilot.memory.store.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 临时工作区服务。
 *
 * @author zsg
 * @since 2026-03-20
 */
public class SessionWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceProperties properties;

    public SessionWorkspaceService(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   WorkspaceProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public WorkspaceItem savePendingDecision(String sessionId, PendingDecisionItem item) {
        return saveDraft(sessionId, WorkspaceItemKind.PENDING_DECISION, item.title(), item.summary(),
                item.payload(), item.priority(), item.taskId(), item.sourceTraceId(),
                coalesceExpiry(item.expiresAt(), properties.getPendingDecisionTtlHours()));
    }

    public WorkspaceItem saveTaskState(String sessionId, TaskStateItem item) {
        return saveDraft(sessionId, WorkspaceItemKind.TASK_STATE, item.title(), item.summary(),
                item.payload(), item.priority(), item.taskId(), item.sourceTraceId(),
                coalesceExpiry(item.expiresAt(), properties.getTaskStateTtlHours()));
    }

    public WorkspaceItem saveWorkingSet(String sessionId, WorkingSetItem item) {
        return saveDraft(sessionId, WorkspaceItemKind.WORKING_SET, item.title(), item.summary(),
                item.payload(), item.priority(), item.taskId(), item.sourceTraceId(),
                coalesceExpiry(item.expiresAt(), properties.getWorkingSetTtlHours()));
    }

    public List<WorkspaceItem> listActive(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, kind, title, summary, payload_json, status, priority,
                       task_id, source_trace_id, expires_at, created_at, updated_at
                FROM session_workspace_items
                WHERE session_id = ?
                  AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY priority DESC, updated_at DESC
                """,
                this::mapRow,
                sessionId,
                Instant.now().toString());
    }

    public Optional<WorkspaceItem> findLatestByTaskOrItemId(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        List<WorkspaceItem> items = jdbcTemplate.query(
                """
                SELECT id, session_id, kind, title, summary, payload_json, status, priority,
                       task_id, source_trace_id, expires_at, created_at, updated_at
                FROM session_workspace_items
                WHERE id = ? OR task_id = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                this::mapRow,
                reference,
                reference
        );
        return items.stream().findFirst();
    }

    public int resolveByTaskId(String sessionId, @Nullable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE session_workspace_items
                SET status = 'RESOLVED', updated_at = ?
                WHERE session_id = ? AND task_id = ? AND status = 'ACTIVE'
                """,
                Instant.now().toString(),
                sessionId,
                taskId);
    }

    public int resolveBySourceTraceId(String sessionId, @Nullable String sourceTraceId) {
        if (sourceTraceId == null || sourceTraceId.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                UPDATE session_workspace_items
                SET status = 'RESOLVED', updated_at = ?
                WHERE session_id = ? AND source_trace_id = ? AND status = 'ACTIVE'
                """,
                Instant.now().toString(),
                sessionId,
                sourceTraceId);
    }

    public int expireDueItems() {
        return jdbcTemplate.update(
                """
                UPDATE session_workspace_items
                SET status = 'EXPIRED', updated_at = ?
                WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?
                """,
                Instant.now().toString(),
                Instant.now().toString());
    }

    public int purgeTerminalItems() {
        Instant cutoff = Instant.now().minusSeconds(properties.getTerminalRetentionHours() * 3600L);
        return jdbcTemplate.update(
                """
                DELETE FROM session_workspace_items
                WHERE status IN ('RESOLVED', 'EXPIRED', 'CANCELLED')
                  AND updated_at <= ?
                """,
                cutoff.toString());
    }

    private WorkspaceItem saveDraft(String sessionId,
                                    WorkspaceItemKind kind,
                                    String title,
                                    String summary,
                                    @Nullable Map<String, Object> payload,
                                    int priority,
                                    @Nullable String taskId,
                                    @Nullable String sourceTraceId,
                                    @Nullable Instant expiresAt) {
        Instant now = Instant.now();
        Optional<WorkspaceItem> existing = findActiveByTaskId(sessionId, kind, taskId);
        String payloadJson = serializePayload(payload);
        if (existing.isPresent()) {
            WorkspaceItem current = existing.get();
            jdbcTemplate.update(
                    """
                    UPDATE session_workspace_items
                    SET title = ?, summary = ?, payload_json = ?, priority = ?, source_trace_id = ?,
                        expires_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    title,
                    summary,
                    payloadJson,
                    priority,
                    normalize(sourceTraceId),
                    toDbTime(expiresAt),
                    now.toString(),
                    current.id());
            return new WorkspaceItem(
                    current.id(),
                    current.sessionId(),
                    current.kind(),
                    title,
                    summary,
                    payloadJson,
                    WorkspaceStatus.ACTIVE,
                    priority,
                    normalize(taskId),
                    normalize(sourceTraceId),
                    expiresAt,
                    current.createdAt(),
                    now);
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO session_workspace_items (
                    id, session_id, kind, title, summary, payload_json, status, priority,
                    task_id, source_trace_id, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                sessionId,
                kind.name(),
                title,
                summary,
                payloadJson,
                WorkspaceStatus.ACTIVE.name(),
                priority,
                normalize(taskId),
                normalize(sourceTraceId),
                toDbTime(expiresAt),
                now.toString(),
                now.toString());
        return new WorkspaceItem(
                id,
                sessionId,
                kind,
                title,
                summary,
                payloadJson,
                WorkspaceStatus.ACTIVE,
                priority,
                normalize(taskId),
                normalize(sourceTraceId),
                expiresAt,
                now,
                now);
    }

    private Optional<WorkspaceItem> findActiveByTaskId(String sessionId,
                                                       WorkspaceItemKind kind,
                                                       @Nullable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        List<WorkspaceItem> items = jdbcTemplate.query(
                """
                SELECT id, session_id, kind, title, summary, payload_json, status, priority,
                       task_id, source_trace_id, expires_at, created_at, updated_at
                FROM session_workspace_items
                WHERE session_id = ? AND kind = ? AND task_id = ? AND status = 'ACTIVE'
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                this::mapRow,
                sessionId,
                kind.name(),
                taskId);
        return items.stream().findFirst();
    }

    private WorkspaceItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceItem(
                rs.getString("id"),
                rs.getString("session_id"),
                WorkspaceItemKind.valueOf(rs.getString("kind")),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("payload_json"),
                WorkspaceStatus.valueOf(rs.getString("status")),
                rs.getInt("priority"),
                rs.getString("task_id"),
                rs.getString("source_trace_id"),
                parseInstant(rs.getString("expires_at")),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")));
    }

    @Nullable
    private String serializePayload(@Nullable Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("工作区 payload 序列化失败，降级为 null: error={}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private Instant coalesceExpiry(@Nullable Instant expiresAt, int ttlHours) {
        if (expiresAt != null) {
            return expiresAt;
        }
        if (ttlHours <= 0) {
            return null;
        }
        return Instant.now().plusSeconds(ttlHours * 3600L);
    }

    @Nullable
    private String toDbTime(@Nullable Instant value) {
        return value != null ? value.toString() : null;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Nullable
    private Instant parseInstant(@Nullable String value) {
        return value != null && !value.isBlank() ? Instant.parse(value) : null;
    }
}
