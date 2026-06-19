package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆投影 outbox 仓库。
 *
 * <p>主库事务只负责写入投影任务；向量/图谱等派生索引由 processor 幂等消费。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryProjectionOutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryProjectionOutboxRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    @Nullable
    private Boolean tableAvailable;

    public MemoryProjectionOutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public String enqueue(String aggregateType,
                          String aggregateId,
                          String projectionType,
                          String operation,
                          Map<String, Object> payload) {
        if (!isTableAvailable()) {
            throw new IllegalStateException("memory_projection_outbox 表不存在，禁止绕过 outbox 写派生索引");
        }
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO memory_projection_outbox(
                        id, aggregate_type, aggregate_id, projection_type,
                        operation, payload_json, status, attempt_count,
                        created_at, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,
                    id,
                    aggregateType,
                    aggregateId,
                    projectionType,
                    operation,
                    objectMapper.writeValueAsString(payload),
                    "PENDING",
                    0,
                    now,
                    now);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("记忆投影 outbox 写入失败: aggregateType=%s, aggregateId=%s, projection=%s, operation=%s"
                    .formatted(aggregateType, aggregateId, projectionType, operation), e);
        }
    }

    public Optional<ProjectionTask> findById(String id) {
        if (!isTableAvailable()) {
            return Optional.empty();
        }
        var rows = jdbcTemplate.query(
                """
                SELECT id, aggregate_type, aggregate_id, projection_type,
                       operation, payload_json, status, attempt_count
                FROM memory_projection_outbox
                WHERE id = ?
                """,
                (rs, rowNum) -> new ProjectionTask(
                        rs.getString("id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("projection_type"),
                        rs.getString("operation"),
                        rs.getString("payload_json"),
                        rs.getString("status"),
                        rs.getInt("attempt_count")
                ),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<String> findDueTaskIds(int limit, Instant processingStaleBefore) {
        if (!isTableAvailable() || limit <= 0) {
            return List.of();
        }
        String now = Instant.now().toString();
        String staleBefore = processingStaleBefore != null
                ? processingStaleBefore.toString()
                : Instant.now().minusSeconds(300).toString();
        return jdbcTemplate.queryForList(
                """
                SELECT id
                FROM memory_projection_outbox
                WHERE status = 'PENDING'
                   OR (status = 'FAILED' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                   OR (status = 'PROCESSING' AND updated_at <= ?)
                ORDER BY created_at ASC
                LIMIT ?
                """,
                String.class,
                now,
                staleBefore,
                limit);
    }

    public boolean markProcessing(String id) {
        if (!isTableAvailable()) {
            return false;
        }
        int affected = jdbcTemplate.update(
                """
                UPDATE memory_projection_outbox
                SET status = 'PROCESSING',
                    updated_at = ?
                WHERE id = ?
                  AND status IN ('PENDING', 'FAILED', 'PROCESSING')
                """,
                Instant.now().toString(),
                id);
        return affected > 0;
    }

    public boolean markProcessing(String id, boolean reclaimProcessing) {
        if (reclaimProcessing) {
            return markProcessing(id);
        }
        if (!isTableAvailable()) {
            return false;
        }
        int affected = jdbcTemplate.update(
                """
                UPDATE memory_projection_outbox
                SET status = 'PROCESSING',
                    updated_at = ?
                WHERE id = ?
                  AND status IN ('PENDING', 'FAILED')
                """,
                Instant.now().toString(),
                id);
        return affected > 0;
    }

    public void markProcessed(String id) {
        if (!isTableAvailable()) {
            return;
        }
        String now = Instant.now().toString();
        jdbcTemplate.update(
                """
                UPDATE memory_projection_outbox
                SET status = 'PROCESSED',
                    processed_at = ?,
                    updated_at = ?,
                    last_error = NULL
                WHERE id = ?
                """,
                now,
                now,
                id);
    }

    public void markFailed(String id, int previousAttemptCount, @Nullable String errorMessage) {
        if (!isTableAvailable()) {
            return;
        }
        String now = Instant.now().toString();
        int nextAttempt = previousAttemptCount + 1;
        Instant nextAttemptAt = Instant.now().plusSeconds(Math.min(300, 5L * nextAttempt));
        jdbcTemplate.update(
                """
                UPDATE memory_projection_outbox
                SET status = 'FAILED',
                    attempt_count = ?,
                    next_attempt_at = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                nextAttempt,
                nextAttemptAt.toString(),
                errorMessage,
                now,
                id);
    }

    public int countByStatus(String status) {
        if (!isTableAvailable()) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_projection_outbox WHERE status = ?",
                Integer.class,
                status);
        return count != null ? count : 0;
    }

    public boolean isTableAvailable() {
        if (tableAvailable != null) {
            return tableAvailable;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='memory_projection_outbox'",
                    Integer.class);
            tableAvailable = count != null && count > 0;
        } catch (Exception e) {
            tableAvailable = false;
        }
        return tableAvailable;
    }

    public record ProjectionTask(
            String id,
            String aggregateType,
            String aggregateId,
            String projectionType,
            String operation,
            String payloadJson,
            String status,
            int attemptCount
    ) {}
}
