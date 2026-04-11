package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.sync.KnowledgeSyncJob;
import com.lifepilot.knowledge.sync.KnowledgeSyncJobStatus;
import com.lifepilot.knowledge.sync.KnowledgeSyncJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识同步任务仓储。
 *
 * @author zsg
 * @since 2026-03-26
 */
public class KnowledgeSyncJobRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncJobRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeSyncJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String enqueue(KnowledgeSyncJobType jobType,
                          String knowledgeBaseId,
                          String datastoreId,
                          String sourceKey,
                          String sourceVersion,
                          Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_sync_jobs (
                    id, job_type, knowledge_base_id, datastore_id, source_key, source_version,
                    payload_json, status, attempt_count, last_error, available_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                jobType.name(),
                knowledgeBaseId,
                datastoreId,
                sourceKey,
                sourceVersion,
                serializeMap(payload),
                KnowledgeSyncJobStatus.PENDING.name(),
                0,
                null,
                now.toString(),
                now.toString(),
                now.toString()
        );
        return id;
    }

    public List<KnowledgeSyncJob> findAvailableJobs(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, job_type, knowledge_base_id, datastore_id, source_key, source_version,
                       payload_json, status, attempt_count, last_error, available_at, created_at, updated_at
                FROM knowledge_sync_jobs
                WHERE status IN ('PENDING', 'FAILED')
                  AND available_at <= ?
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new KnowledgeSyncJob(
                        rs.getString("id"),
                        KnowledgeSyncJobType.valueOf(rs.getString("job_type")),
                        rs.getString("knowledge_base_id"),
                        rs.getString("datastore_id"),
                        rs.getString("source_key"),
                        rs.getString("source_version"),
                        deserializeMap(rs.getString("payload_json")),
                        KnowledgeSyncJobStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error"),
                        Instant.parse(rs.getString("available_at")),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at"))
                ),
                Instant.now().toString(),
                limit
        );
    }

    public boolean markProcessing(String id) {
        int rows = jdbcTemplate.update(
                """
                UPDATE knowledge_sync_jobs
                SET status = ?, updated_at = ?
                WHERE id = ? AND status IN ('PENDING', 'FAILED')
                """,
                KnowledgeSyncJobStatus.PROCESSING.name(),
                Instant.now().toString(),
                id
        );
        return rows > 0;
    }

    public void markCompleted(String id) {
        jdbcTemplate.update(
                """
                UPDATE knowledge_sync_jobs
                SET status = ?, updated_at = ?, last_error = NULL
                WHERE id = ?
                """,
                KnowledgeSyncJobStatus.COMPLETED.name(),
                Instant.now().toString(),
                id
        );
    }

    public void markFailed(String id, String errorMessage, int attemptCount, Instant availableAt) {
        jdbcTemplate.update(
                """
                UPDATE knowledge_sync_jobs
                SET status = ?, attempt_count = ?, last_error = ?, available_at = ?, updated_at = ?
                WHERE id = ?
                """,
                KnowledgeSyncJobStatus.FAILED.name(),
                attemptCount,
                errorMessage,
                availableAt.toString(),
                Instant.now().toString(),
                id
        );
    }

    private String serializeMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("同步任务 payload 序列化失败，使用空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("同步任务 payload 反序列化失败，返回空对象: error={}", e.getMessage());
            return Map.of();
        }
    }
}
