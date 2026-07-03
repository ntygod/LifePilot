package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemoryProjectionOutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    public String enqueue(String aggregateType,
                          String aggregateId,
                          String projectionType,
                          String operation,
                          Map<String, Object> payload) {
        requireText(aggregateType, "aggregateType 不能为空");
        requireText(aggregateId, "aggregateId 不能为空");
        requireText(projectionType, "projectionType 不能为空");
        requireText(operation, "operation 不能为空");
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try {
            int inserted = jdbcTemplate.update(
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
                    objectMapper.writeValueAsString(Objects.requireNonNull(payload, "payload 不能为空")),
                    "PENDING",
                    0,
                    now,
                    now);
            if (inserted != 1) {
                throw new IllegalStateException("记忆投影 outbox 写入行数异常: inserted=" + inserted);
            }
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("记忆投影 outbox 写入失败: aggregateType=%s, aggregateId=%s, projection=%s, operation=%s"
                    .formatted(aggregateType, aggregateId, projectionType, operation), e);
        }
    }

    public Optional<ProjectionTask> findById(String id) {
        requireText(id, "outbox id 不能为空");
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
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        Objects.requireNonNull(processingStaleBefore, "processingStaleBefore 不能为空");
        String now = Instant.now().toString();
        String staleBefore = processingStaleBefore.toString();
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
        requireText(id, "outbox id 不能为空");
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
        return requireClaimResult(affected, id);
    }

    public boolean markProcessing(String id, boolean reclaimProcessing) {
        if (reclaimProcessing) {
            return markProcessing(id);
        }
        requireText(id, "outbox id 不能为空");
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
        return requireClaimResult(affected, id);
    }

    public void markProcessed(String id) {
        requireText(id, "outbox id 不能为空");
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update(
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
        if (updated != 1) {
            throw new IllegalStateException("记忆投影 outbox 标记 PROCESSED 失败: id=" + id
                    + ", updated=" + updated);
        }
    }

    public void markFailed(String id, int previousAttemptCount, @Nullable String errorMessage) {
        requireText(id, "outbox id 不能为空");
        if (previousAttemptCount < 0) {
            throw new IllegalArgumentException("previousAttemptCount 不能为负数: " + previousAttemptCount);
        }
        String now = Instant.now().toString();
        int nextAttempt = previousAttemptCount + 1;
        Instant nextAttemptAt = Instant.now().plusSeconds(Math.min(300, 5L * nextAttempt));
        int updated = jdbcTemplate.update(
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
        if (updated != 1) {
            throw new IllegalStateException("记忆投影 outbox 标记 FAILED 失败: id=" + id
                    + ", updated=" + updated);
        }
    }

    public int countByStatus(String status) {
        requireText(status, "status 不能为空");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_projection_outbox WHERE status = ?",
                Integer.class,
                status);
        if (count == null) {
            throw new IllegalStateException("记忆投影 outbox 状态计数结果不能为空");
        }
        return count;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean requireClaimResult(int affected, String id) {
        if (affected == 0) {
            return false;
        }
        if (affected == 1) {
            return true;
        }
        throw new IllegalStateException("记忆投影 outbox 认领影响行数异常: id=" + id + ", affected=" + affected);
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
    ) {
        public ProjectionTask {
            requireText(id, "任务 id 不能为空");
            requireText(aggregateType, "任务 aggregateType 不能为空");
            requireText(aggregateId, "任务 aggregateId 不能为空");
            requireText(projectionType, "任务 projectionType 不能为空");
            requireText(operation, "任务 operation 不能为空");
            requireText(payloadJson, "任务 payloadJson 不能为空");
            requireText(status, "任务 status 不能为空");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("任务 attemptCount 不能为负数: " + attemptCount);
            }
        }
    }
}
