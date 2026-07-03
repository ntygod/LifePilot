package com.lifepilot.agent.learning.conflict;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.semantic.ConflictVerdict;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 冲突裁决队列访问层 — 操作 {@code conflict_resolution_queue} 表。
 *
 * <p>生命周期：
 * <ol>
 *     <li>{@link #enqueue} 在主 upsert 产出新实体后入队，状态 {@code PENDING}；</li>
 *     <li>{@link ConflictResolutionService} 异步拉起 LLM 裁决后，成功走
 *         {@link #markResolved}，失败走 {@link #markFailed}；</li>
 *     <li>重试 Cron 通过 {@link #findFailedRetriable} 拉取
 *         可重试项再次入队。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ConflictResolutionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConflictResolutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 将一对"新实体 + 候选旧实体列表"入队等待 LLM 裁决。
     *
     * @param newEntityId   upsert 产出的新实体 ID
     * @param candidateIds  高相似度候选旧实体 ID 列表（已由调用方过滤）
     * @return 新建队列项的 id
     */
    public String enqueue(String newEntityId, List<String> candidateIds) {
        Objects.requireNonNull(newEntityId, "newEntityId 不能为空");
        if (newEntityId.isBlank()) {
            throw new IllegalArgumentException("newEntityId 不能为空");
        }
        if (candidateIds == null || candidateIds.isEmpty()) {
            throw new IllegalArgumentException("candidateIds 不能为空");
        }
        for (String candidateId : candidateIds) {
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException("candidateIds 不能包含空值");
            }
            if (!candidateId.equals(candidateId.trim())) {
                throw new IllegalArgumentException("candidateIds 不能包含首尾空白: " + candidateId);
            }
        }
        var id = UUID.randomUUID().toString();
        String candidatesJson;
        try {
            candidatesJson = objectMapper.writeValueAsString(candidateIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("候选列表 JSON 序列化失败: " + candidateIds, e);
        }
        int rows = jdbcTemplate.update(
                """
                INSERT INTO conflict_resolution_queue
                    (id, new_entity_id, candidate_entity_ids, status, attempt_count, created_at)
                VALUES (?, ?, ?, 'PENDING', 0, ?)
                """,
                id, newEntityId, candidatesJson, Instant.now().toString());
        requireSingleRow(rows, "冲突裁决队列入队", id);
        return id;
    }

    /**
     * LLM 成功裁决并应用完成后，将队列项标记为 {@code RESOLVED} 并记录 verdict。
     *
     * @param id      队列项 id
     * @param verdict LLM 裁决结果
     */
    public void markResolved(String id, ConflictVerdict verdict) {
        requireId(id);
        Objects.requireNonNull(verdict, "verdict 不能为空");
        int rows = jdbcTemplate.update(
                """
                UPDATE conflict_resolution_queue
                SET status = 'RESOLVED',
                    verdict = ?,
                    rationale = ?,
                    resolved_at = ?
                WHERE id = ?
                """,
                verdict.verdict().name(), verdict.rationale(),
                Instant.now().toString(), id);
        requireSingleRow(rows, "冲突裁决队列标记 RESOLVED", id);
    }

    /**
     * 裁决失败时标记为 {@code FAILED} 并累计 attempt_count —— 供重试 Cron
     * 判断是否继续重试。
     *
     * @param id     队列项 id
     * @param reason 失败原因（写入 rationale，便于运维定位）
     */
    public void markFailed(String id, String reason) {
        requireId(id);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("失败原因不能为空");
        }
        int rows = jdbcTemplate.update(
                """
                UPDATE conflict_resolution_queue
                SET status = 'FAILED',
                    rationale = ?,
                    attempt_count = attempt_count + 1
                WHERE id = ?
                """,
                reason, id);
        requireSingleRow(rows, "冲突裁决队列标记 FAILED", id);
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("队列项 id 不能为空");
        }
    }

    private static void requireSingleRow(int rows, String operation, String id) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响行数必须为 1, id=" + id + ", rows=" + rows);
        }
    }

    /**
     * 查询可重试的 {@code FAILED} 项，供 ConflictResolutionRetry Cron 使用。
     *
     * @param maxAttempts 最大重试次数（即 attempt_count &lt; maxAttempts 才返回）
     * @return 符合条件的队列项，按 created_at 升序
     */
    public List<QueueItem> findFailedRetriable(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts 必须大于 0");
        }
        return jdbcTemplate.query(
                """
                SELECT id, new_entity_id, candidate_entity_ids
                FROM conflict_resolution_queue
                WHERE status = 'FAILED' AND attempt_count < ?
                ORDER BY created_at ASC
                """,
                (rs, rowNum) -> {
                    var json = rs.getString("candidate_entity_ids");
                    if (json == null || json.isBlank()) {
                        throw new IllegalStateException(
                                "候选列表不能为空, id=" + rs.getString("id"));
                    }
                    List<String> candidates;
                    try {
                        var array = objectMapper.readValue(json, String[].class);
                        if (array == null || array.length == 0
                                || Arrays.stream(array).anyMatch(candidateId ->
                                candidateId == null || candidateId.isBlank())) {
                            throw new IllegalStateException(
                                    "候选列表不能为空, id=" + rs.getString("id"));
                        }
                        candidates = Arrays.stream(array).toList();
                        for (String candidateId : candidates) {
                            if (!candidateId.equals(candidateId.trim())) {
                                throw new IllegalStateException(
                                        "候选列表不能包含首尾空白, id=" + rs.getString("id"));
                            }
                        }
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(
                                "候选列表反序列化失败, id=" + rs.getString("id"), e);
                    }
                    return new QueueItem(
                            rs.getString("id"),
                            rs.getString("new_entity_id"),
                            candidates);
                },
                maxAttempts);
    }

    /**
     * 队列项轻量视图 — 仅承载重试 Cron 需要的字段。
     *
     * @param id                 队列项 id
     * @param newEntityId        新实体 ID
     * @param candidateEntityIds 候选旧实体 ID 列表
     */
    public record QueueItem(String id, String newEntityId, List<String> candidateEntityIds) {
        public QueueItem {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("队列项 id 不能为空");
            }
            if (newEntityId == null || newEntityId.isBlank()) {
                throw new IllegalArgumentException("队列项 newEntityId 不能为空");
            }
            Objects.requireNonNull(candidateEntityIds, "candidateEntityIds 不能为空");
            if (candidateEntityIds.isEmpty()) {
                throw new IllegalArgumentException("candidateEntityIds 不能为空");
            }
            for (String candidateEntityId : candidateEntityIds) {
                if (candidateEntityId == null || candidateEntityId.isBlank()) {
                    throw new IllegalArgumentException("candidateEntityId 不能为空");
                }
            }
            candidateEntityIds = List.copyOf(candidateEntityIds);
        }
    }
}
