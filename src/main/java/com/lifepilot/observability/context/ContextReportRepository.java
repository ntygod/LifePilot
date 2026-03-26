package com.lifepilot.observability.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
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
 * 上下文报告仓储。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Repository
public class ContextReportRepository {

    private static final Logger log = LoggerFactory.getLogger(ContextReportRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreRepository sessionStoreRepository;

    public record ContextReportRow(
            String id,
            String sessionId,
            @Nullable String traceId,
            int systemPromptTokens,
            int transcriptTokens,
            int memoryTokens,
            int artifactTokens,
            int toolSchemaTokens,
            int toolResultTokens,
            boolean pruningApplied,
            boolean compactionApplied,
            int contextWindow,
            int reservedTokens,
            String payloadJson,
            Instant createdAt
    ) {
    }

    public ContextReportRepository(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   SessionStoreRepository sessionStoreRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sessionStoreRepository = sessionStoreRepository;
    }

    @Autowired
    public ContextReportRepository(JdbcTemplate jdbcTemplate,
                                   SessionStoreRepository sessionStoreRepository) {
        this(jdbcTemplate, new ObjectMapper(), sessionStoreRepository);
    }

    public String save(String sessionId,
                       @Nullable String traceId,
                       int systemPromptTokens,
                       int transcriptTokens,
                       int memoryTokens,
                       int artifactTokens,
                       int toolSchemaTokens,
                       int toolResultTokens,
                       boolean pruningApplied,
                       boolean compactionApplied,
                       int contextWindow,
                       int reservedTokens,
                       @Nullable Map<String, Object> payload,
                       @Nullable Instant createdAt) {
        sessionStoreRepository.ensureSessionShell(sessionId);
        String id = UUID.randomUUID().toString();
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        jdbcTemplate.update("""
                INSERT INTO context_reports (
                    id, session_id, trace_id, system_prompt_tokens, transcript_tokens,
                    memory_tokens, artifact_tokens, tool_schema_tokens, tool_result_tokens,
                    pruning_applied, compaction_applied, context_window, reserved_tokens, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                sessionId,
                traceId,
                systemPromptTokens,
                transcriptTokens,
                memoryTokens,
                artifactTokens,
                toolSchemaTokens,
                toolResultTokens,
                pruningApplied ? 1 : 0,
                compactionApplied ? 1 : 0,
                contextWindow,
                reservedTokens,
                serializePayload(payload),
                timestamp.toString()
        );
        sessionStoreRepository.updateContextEstimate(
                sessionId,
                systemPromptTokens + transcriptTokens + memoryTokens + toolSchemaTokens + toolResultTokens,
                timestamp
        );
        return id;
    }

    public Optional<ContextReportRow> findLatestBySessionId(String sessionId) {
        List<ContextReportRow> rows = jdbcTemplate.query("""
                SELECT * FROM context_reports
                WHERE session_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """,
                this::mapRow,
                sessionId
        );
        return rows.stream().findFirst();
    }

    public Map<String, Object> readPayload(String reportId) {
        try {
            String payloadJson = jdbcTemplate.queryForObject(
                    "SELECT payload_json FROM context_reports WHERE id = ?",
                    String.class,
                    reportId
            );
            return deserializePayload(payloadJson);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ContextReportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ContextReportRow(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getInt("system_prompt_tokens"),
                rs.getInt("transcript_tokens"),
                rs.getInt("memory_tokens"),
                rs.getInt("artifact_tokens"),
                rs.getInt("tool_schema_tokens"),
                rs.getInt("tool_result_tokens"),
                rs.getInt("pruning_applied") == 1,
                rs.getInt("compaction_applied") == 1,
                rs.getInt("context_window"),
                rs.getInt("reserved_tokens"),
                rs.getString("payload_json"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private String serializePayload(@Nullable Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("context report payload 序列化失败，写入空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> deserializePayload(@Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() { });
            return payload != null ? payload : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("context report payload 反序列化失败: error={}", e.getMessage());
            return new HashMap<>();
        }
    }
}
