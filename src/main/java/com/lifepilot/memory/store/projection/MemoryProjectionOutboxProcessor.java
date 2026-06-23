package com.lifepilot.memory.store.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;

/**
 * 记忆投影 outbox 消费器。
 *
 * <p>当前落地 VECTOR 投影；后续 GRAPH/DERIVED 可以复用同一 outbox。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryProjectionOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(MemoryProjectionOutboxProcessor.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MemoryProjectionOutboxRepository repository;
    private final VectorSearcher vectorSearcher;
    private final ObjectMapper objectMapper;

    public MemoryProjectionOutboxProcessor(MemoryProjectionOutboxRepository repository,
                                           VectorSearcher vectorSearcher,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.vectorSearcher = vectorSearcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费单个 outbox 任务。
     *
     * <p>重复调用是幂等的：已处理任务会被 markProcessing 拒绝；向量 UPSERT/DELETE
     * 自身也按 entityId 幂等。</p>
     */
    public void processOne(String outboxId) {
        processOne(outboxId, false);
    }

    private boolean processOne(String outboxId, boolean reclaimProcessing) {
        var taskOpt = repository.findById(outboxId);
        if (taskOpt.isEmpty()) {
            return false;
        }
        var task = taskOpt.get();
        if (!repository.markProcessing(outboxId, reclaimProcessing)) {
            return false;
        }
        try {
            processTask(task);
            repository.markProcessed(outboxId);
        } catch (Exception e) {
            repository.markFailed(outboxId, task.attemptCount(), e.getMessage());
            log.warn("记忆投影任务执行失败: id={}, projection={}, operation={}, error={}",
                    outboxId, task.projectionType(), task.operation(), e.getMessage());
        }
        return true;
    }

    public int processDue(int limit, Duration processingTimeout) {
        Duration timeout = processingTimeout != null ? processingTimeout : Duration.ofMinutes(5);
        var ids = repository.findDueTaskIds(limit, Instant.now().minus(timeout));
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
