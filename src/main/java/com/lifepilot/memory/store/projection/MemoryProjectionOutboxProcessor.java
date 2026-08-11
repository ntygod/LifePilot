package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.retrieval.VectorSearcher;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 记忆投影 outbox 消费器。
 *
 * <p>当前落地 VECTOR 投影；后续 GRAPH/DERIVED 可以复用同一 outbox。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryProjectionOutboxProcessor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MemoryProjectionOutboxRepository repository;
    private final VectorSearcher vectorSearcher;
    private final ObjectMapper objectMapper;

    public MemoryProjectionOutboxProcessor(MemoryProjectionOutboxRepository repository,
                                           VectorSearcher vectorSearcher,
                                           ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "MemoryProjectionOutboxRepository 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "VectorSearcher 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    /**
     * 消费单个 outbox 任务。
     *
     * <p>重复调用是幂等的：已处理任务会被 markProcessing 拒绝；向量 UPSERT/DELETE
     * 自身也按 entityId 幂等。</p>
     */
    public void processOne(String outboxId) {
        if (outboxId == null || outboxId.isBlank()) {
            throw new IllegalArgumentException("outboxId 不能为空");
        }
        processOne(outboxId, false);
    }

    private boolean processOne(String outboxId, boolean reclaimProcessing) {
        var taskOpt = repository.findById(outboxId);
        if (taskOpt.isEmpty()) {
            throw new IllegalStateException("记忆投影任务不存在: id=" + outboxId);
        }
        var task = taskOpt.get();
        if (!repository.markProcessing(outboxId, reclaimProcessing)) {
            return false;
        }
        try {
            processTask(task);
            repository.markProcessed(outboxId);
        } catch (Exception e) {
            try {
                repository.markFailed(outboxId, task.attemptCount(), e.getMessage());
            } catch (Exception markFailure) {
                e.addSuppressed(markFailure);
            }
            throw new IllegalStateException(
                    "记忆投影任务执行失败: id=%s, projection=%s, operation=%s"
                            .formatted(outboxId, task.projectionType(), task.operation()),
                    e);
        }
        return true;
    }

    public int processDue(int limit, Duration processingTimeout) {
        Objects.requireNonNull(processingTimeout, "processingTimeout 不能为空");
        var ids = Objects.requireNonNull(
                repository.findDueTaskIds(limit, Instant.now().minus(processingTimeout)),
                "待处理 outbox ID 查询结果不能为空");
        int processed = 0;
        for (String id : ids) {
            if (processOne(id, true)) {
                processed++;
            }
        }
        return processed;
    }

    private void processTask(MemoryProjectionOutboxRepository.ProjectionTask task) throws Exception {
        if (!"VECTOR".equals(task.projectionType()) && !"PROCEDURE_TEMPLATE_VECTOR".equals(task.projectionType())) {
            throw new IllegalArgumentException("未知投影类型: " + task.projectionType());
        }
        Map<String, Object> payload = objectMapper.readValue(task.payloadJson(), MAP_TYPE);
        String entityId = requireText(payload, "entityId");
        switch (task.operation()) {
            case "UPSERT" -> {
                String text = requireText(payload, "text");
                vectorSearcher.upsertEntityVector(entityId, text);
            }
            case "DELETE" -> vectorSearcher.deleteEntityVector(entityId);
            default -> throw new IllegalArgumentException("未知投影操作: " + task.operation());
        }
    }

    private String requireText(Map<String, Object> payload, String fieldName) {
        Object value = payload.get(fieldName);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("投影 payload 缺少 " + fieldName);
        }
        return value.toString();
    }
}
