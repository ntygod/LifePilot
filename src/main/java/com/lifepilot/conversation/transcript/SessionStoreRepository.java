package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transcript 会话元数据仓储。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Repository
public class SessionStoreRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    @Nullable
    private final MemoryEventBus memoryEventBus;

    public record SessionStoreRow(
            String sessionId,
            String channel,
            String chatType,
            String title,
            @Nullable String summary,
            int messageCount,
            boolean pinned,
            boolean archived,
            @Nullable Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt,
            Instant lastActivityAt,
            String configJson,
            int contextTokensEstimate,
            int compactionCount,
            @Nullable Instant memoryFlushAt,
            String activeBranchId
    ) {
    }

    public SessionStoreRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public SessionStoreRepository(JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  @Nullable MemoryEventBus memoryEventBus) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memoryEventBus = memoryEventBus;
    }

    public void save(ChatSession session) {
        boolean created = !exists(session.id());
        String channel = resolveChannel(session.id());
        String updatedAt = session.updatedAt().toString();
        String lastActivityAt = session.lastMessageAt() != null
                ? session.lastMessageAt().toString()
                : updatedAt;
        jdbcTemplate.update("""
                INSERT INTO session_store (
                    session_id, channel, chat_type, title, summary, message_count,
                    is_pinned, archived, last_message_at, created_at, updated_at, last_activity_at, active_branch_id
                ) VALUES (?, ?, 'chat', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'main')
                ON CONFLICT(session_id) DO UPDATE SET
                    channel = excluded.channel,
                    chat_type = excluded.chat_type,
                    title = excluded.title,
                    summary = excluded.summary,
                    message_count = excluded.message_count,
                    is_pinned = excluded.is_pinned,
                    archived = excluded.archived,
                    last_message_at = excluded.last_message_at,
                    updated_at = excluded.updated_at,
                    last_activity_at = excluded.last_activity_at,
                    active_branch_id = excluded.active_branch_id
                """,
                session.id(),
                channel,
                session.title(),
                session.summary(),
                session.messageCount(),
                session.isPinned() ? 1 : 0,
                session.archived() ? 1 : 0,
                session.lastMessageAt() != null ? session.lastMessageAt().toString() : null,
                session.createdAt().toString(),
                updatedAt,
                lastActivityAt
        );
        if (created) {
            publishEvent(new MemoryEvent.SessionStarted(
                    session.id(),
                    channel,
                    session.title(),
                    session.createdAt()
            ));
        }
    }

    public void ensureSessionShell(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (exists(sessionId)) {
            return;
        }
        String now = Instant.now().toString();
        String channel = resolveChannel(sessionId);
        String title = deriveDefaultTitle(sessionId);
        int inserted = jdbcTemplate.update("""
                INSERT INTO session_store (
                    session_id, channel, chat_type, title, created_at, updated_at, last_activity_at, active_branch_id
                ) VALUES (?, ?, 'chat', ?, ?, ?, ?, 'main')
                ON CONFLICT(session_id) DO NOTHING
                """,
                sessionId,
                channel,
                title,
                now,
                now,
                now
        );
        if (inserted > 0) {
            publishEvent(new MemoryEvent.SessionStarted(
                    sessionId,
                    channel,
                    title,
                    Instant.parse(now)
            ));
        }
    }

    public void appendMessageMeta(String sessionId, @Nullable Instant messageAt, @Nullable String lastMessagePreview) {
        ensureSessionShell(sessionId);
        String at = (messageAt != null ? messageAt : Instant.now()).toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    message_count = message_count + 1,
                    last_message_at = ?,
                    summary = ?,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                at, lastMessagePreview, at, at, sessionId
        );
    }

    public void updateTitle(String sessionId, String title) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE session_store SET title = ?, updated_at = ?, last_activity_at = ? WHERE session_id = ?",
                title, now, now, sessionId
        );
    }

    public void updatePinned(String sessionId, boolean pinned) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE session_store SET is_pinned = ?, updated_at = ?, last_activity_at = ? WHERE session_id = ?",
                pinned ? 1 : 0, now, now, sessionId
        );
    }

    public void updateArchived(String sessionId, boolean archived) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE session_store SET archived = ?, updated_at = ?, last_activity_at = ? WHERE session_id = ?",
                archived ? 1 : 0, now, now, sessionId
        );
    }

    public void updateFields(String sessionId,
                             @Nullable String title,
                             @Nullable Boolean pinned,
                             @Nullable Boolean archived) {
        ensureSessionShell(sessionId);
        StringBuilder sql = new StringBuilder("UPDATE session_store SET updated_at = ?, last_activity_at = ?");
        List<Object> params = new ArrayList<>();
        String now = Instant.now().toString();
        params.add(now);
        params.add(now);
        if (title != null) {
            sql.append(", title = ?");
            params.add(title);
        }
        if (pinned != null) {
            sql.append(", is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }
        if (archived != null) {
            sql.append(", archived = ?");
            params.add(archived ? 1 : 0);
        }
        sql.append(" WHERE session_id = ?");
        params.add(sessionId);
        jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public void incrementMessageCount(String sessionId) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    message_count = message_count + 1,
                    last_message_at = ?,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                now, now, now, sessionId
        );
    }

    public void clearMessages(String sessionId) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    message_count = 0,
                    summary = NULL,
                    last_message_at = NULL,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                now, now, sessionId
        );
    }

    public void touchActivity(String sessionId, @Nullable Instant activityAt) {
        ensureSessionShell(sessionId);
        String at = (activityAt != null ? activityAt : Instant.now()).toString();
        jdbcTemplate.update(
                "UPDATE session_store SET updated_at = ?, last_activity_at = ? WHERE session_id = ?",
                at, at, sessionId
        );
    }

    public void updateContextEstimate(String sessionId, int contextTokensEstimate, @Nullable Instant activityAt) {
        ensureSessionShell(sessionId);
        String at = (activityAt != null ? activityAt : Instant.now()).toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    context_tokens_estimate = ?,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                Math.max(0, contextTokensEstimate),
                at,
                at,
                sessionId
        );
    }

    public void incrementCompactionCount(String sessionId, @Nullable Instant compactedAt) {
        ensureSessionShell(sessionId);
        String at = (compactedAt != null ? compactedAt : Instant.now()).toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    compaction_count = compaction_count + 1,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                at,
                at,
                sessionId
        );
    }

    public void updateMemoryFlushAt(String sessionId, @Nullable Instant flushedAt) {
        ensureSessionShell(sessionId);
        String at = (flushedAt != null ? flushedAt : Instant.now()).toString();
        jdbcTemplate.update("""
                UPDATE session_store SET
                    memory_flush_at = ?,
                    updated_at = ?,
                    last_activity_at = ?
                WHERE session_id = ?
                """,
                at,
                at,
                at,
                sessionId
        );
    }

    public void updateConfig(String sessionId, Map<String, Object> config) {
        ensureSessionShell(sessionId);
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE session_store SET config_json = ?, updated_at = ?, last_activity_at = ? WHERE session_id = ?",
                serializeConfig(config), now, now, sessionId
        );
    }

    public void deleteById(String sessionId) {
        jdbcTemplate.update("DELETE FROM session_store WHERE session_id = ?", sessionId);
    }

    public int batchUpdateFields(List<String> sessionIds,
                                 @Nullable String title,
                                 @Nullable Boolean pinned,
                                 @Nullable Boolean archived) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE session_store SET updated_at = ?, last_activity_at = ?");
        List<Object> params = new ArrayList<>();
        String now = Instant.now().toString();
        params.add(now);
        params.add(now);
        if (title != null) {
            sql.append(", title = ?");
            params.add(title);
        }
        if (pinned != null) {
            sql.append(", is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }
        if (archived != null) {
            sql.append(", archived = ?");
            params.add(archived ? 1 : 0);
        }
        String placeholders = sessionIds.stream()
                .map(id -> "?")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        sql.append(" WHERE session_id IN (").append(placeholders).append(")");
        params.addAll(sessionIds);
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public int batchDelete(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        String placeholders = sessionIds.stream()
                .map(id -> "?")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return jdbcTemplate.update(
                "DELETE FROM session_store WHERE session_id IN (" + placeholders + ")",
                sessionIds.toArray()
        );
    }

    public Optional<SessionStoreRow> findBySessionId(String sessionId) {
        List<SessionStoreRow> rows = jdbcTemplate.query(
                "SELECT * FROM session_store WHERE session_id = ?",
                this::mapRow,
                sessionId
        );
        return rows.stream().findFirst();
    }

    public List<SessionStoreRow> findWebSessions() {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM session_store
                WHERE channel = 'web'
                  AND instr(session_id, ':') = 0
                ORDER BY is_pinned DESC, last_message_at DESC NULLS LAST, updated_at DESC
                """,
                this::mapRow
        );
    }

    @SuppressWarnings("null")
    public List<SessionStoreRow> findWebSessionsByConditions(@Nullable String q,
                                                             @Nullable Boolean pinned,
                                                             @Nullable Boolean archived,
                                                             @Nullable String timeRange,
                                                             @Nullable String sortBy,
                                                             @Nullable String order) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM session_store
                WHERE channel = 'web'
                  AND instr(session_id, ':') = 0
                """);
        List<Object> params = new ArrayList<>();

        if (q != null && !q.isBlank()) {
            sql.append(" AND (title LIKE ? OR summary LIKE ?)");
            String searchPattern = "%" + q + "%";
            params.add(searchPattern);
            params.add(searchPattern);
        }

        if (pinned != null) {
            sql.append(" AND is_pinned = ?");
            params.add(pinned ? 1 : 0);
        }

        if (archived != null) {
            sql.append(" AND archived = ?");
            params.add(archived ? 1 : 0);
        }

        if (timeRange != null && !timeRange.isBlank()) {
            Instant cutoff;
            if ("7d".equals(timeRange)) {
                cutoff = Instant.now().minusSeconds(7L * 24 * 60 * 60);
            } else if ("30d".equals(timeRange)) {
                cutoff = Instant.now().minusSeconds(30L * 24 * 60 * 60);
            } else {
                cutoff = null;
            }
            if (cutoff != null) {
                sql.append(" AND updated_at >= ?");
                params.add(cutoff.toString());
            }
        }

        String sortField = "updatedAt".equals(sortBy) ? "updated_at" : "last_message_at";
        String sortOrder = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
        sql.append(" ORDER BY is_pinned DESC, ").append(sortField).append(" ").append(sortOrder);
        if ("last_message_at".equals(sortField)) {
            sql.append(" NULLS LAST");
        }
        sql.append(", updated_at DESC");

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    public Map<String, Object> getConfig(String sessionId) {
        try {
            String configJson = jdbcTemplate.queryForObject(
                    "SELECT config_json FROM session_store WHERE session_id = ?",
                    String.class,
                    sessionId
            );
            return deserializeConfig(configJson);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean exists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_store WHERE session_id = ?",
                Integer.class,
                sessionId
        );
        return count != null && count > 0;
    }

    private SessionStoreRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionStoreRow(
                rs.getString("session_id"),
                rs.getString("channel"),
                rs.getString("chat_type"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getInt("message_count"),
                rs.getInt("is_pinned") == 1,
                rs.getInt("archived") == 1,
                parseInstant(rs.getString("last_message_at")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                Instant.parse(rs.getString("last_activity_at")),
                rs.getString("config_json"),
                rs.getInt("context_tokens_estimate"),
                rs.getInt("compaction_count"),
                parseInstant(rs.getString("memory_flush_at")),
                rs.getString("active_branch_id")
        );
    }

    @Nullable
    private Instant parseInstant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Instant.parse(raw);
    }

    private String resolveChannel(String sessionId) {
        int index = sessionId.indexOf(':');
        if (index > 0) {
            return sessionId.substring(0, index);
        }
        return "web";
    }

    private String deriveDefaultTitle(String sessionId) {
        return switch (resolveChannel(sessionId)) {
            case "feishu" -> "飞书对话";
            case "wecom" -> "企微对话";
            case "dingtalk" -> "钉钉对话";
            case "web" -> "New Chat";
            default -> "External Chat";
        };
    }

    private String serializeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.warn("session_store 配置序列化失败，写入空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> deserializeConfig(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : new HashMap<>();
        } catch (JsonProcessingException e) {
            log.warn("session_store 配置反序列化失败: error={}", e.getMessage());
            return new HashMap<>();
        }
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
}
