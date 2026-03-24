package com.lifepilot.conversation.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话 Artifact 仓储。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Repository
public class SessionArtifactRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreRepository sessionStoreRepository;
    @Nullable
    private final MemoryEventBus memoryEventBus;

    public record SessionArtifactRow(
            String id,
            String sessionId,
            @Nullable String sourceEntryId,
            @Nullable String traceId,
            String artifactType,
            @Nullable String title,
            @Nullable String summary,
            String status,
            String payloadJson,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public SessionArtifactRepository(JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper,
                                     SessionStoreRepository sessionStoreRepository) {
        this(jdbcTemplate, objectMapper, sessionStoreRepository, null);
    }

    @Autowired
    public SessionArtifactRepository(JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper,
                                     SessionStoreRepository sessionStoreRepository,
                                     @Nullable MemoryEventBus memoryEventBus) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sessionStoreRepository = sessionStoreRepository;
        this.memoryEventBus = memoryEventBus;
    }

    public String save(String sessionId,
                       @Nullable String sourceEntryId,
                       @Nullable String traceId,
                       String artifactType,
                       @Nullable String title,
                       @Nullable String summary,
                       Map<String, Object> payload,
                       @Nullable String status,
                       @Nullable Instant createdAt) {
        sessionStoreRepository.ensureSessionShell(sessionId);
        String id = UUID.randomUUID().toString();
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        String effectiveStatus = status != null && !status.isBlank() ? status : "ACTIVE";
        jdbcTemplate.update("""
                INSERT INTO session_artifacts (
                    id, session_id, source_entry_id, trace_id, artifact_type, title,
                    summary, payload_json, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                sessionId,
                sourceEntryId,
                traceId,
                artifactType,
                title,
                summary,
                serializePayload(payload),
                effectiveStatus,
                timestamp.toString(),
                timestamp.toString()
        );
        publishEvent(new MemoryEvent.ArtifactCommitted(
                sessionId,
                id,
                sourceEntryId,
                traceId,
                artifactType,
                title,
                timestamp
        ));
        return id;
    }

    public Optional<SessionArtifactRow> findById(String artifactId) {
        List<SessionArtifactRow> rows = jdbcTemplate.query(
                "SELECT * FROM session_artifacts WHERE id = ?",
                this::mapRow,
                artifactId
        );
        return rows.stream().findFirst();
    }

    public List<SessionArtifactRow> findBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                SELECT * FROM session_artifacts
                WHERE session_id = ?
                ORDER BY created_at DESC, id DESC
                """,
                this::mapRow,
                sessionId
        );
    }

    public Map<String, Object> readPayload(String artifactId) {
        return findById(artifactId)
                .map(SessionArtifactRow::payloadJson)
                .map(this::deserializePayload)
                .orElse(Map.of());
    }

    private SessionArtifactRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SessionArtifactRow(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("source_entry_id"),
                rs.getString("trace_id"),
                rs.getString("artifact_type"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("status"),
                rs.getString("payload_json"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("artifact payload 序列化失败，写入空对象: error={}", e.getMessage());
            return "{}";
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
            log.warn("artifact payload 反序列化失败: error={}", e.getMessage());
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
            log.warn("发布 Artifact 事件失败: type={}, sessionId={}, error={}",
                    event.eventType(), event.sessionId(), e.getMessage());
        }
    }
}
