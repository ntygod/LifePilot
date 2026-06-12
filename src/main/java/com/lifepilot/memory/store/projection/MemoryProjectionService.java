package com.lifepilot.memory.store.projection;

import com.lifepilot.memory.store.entity.TemporalEntity;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 记忆派生投影服务。
 *
 * <p>所有派生索引写入都必须走 outbox，不再保留事务后直写向量的兼容路径。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryProjectionService {

    private final MemoryProjectionOutboxRepository repository;
    private final MemoryProjectionOutboxProcessor processor;

    public MemoryProjectionService(MemoryProjectionOutboxRepository repository,
                                   MemoryProjectionOutboxProcessor processor) {
        this.repository = repository;
        this.processor = processor;
    }

    public void enqueueVectorUpsertAfterCommit(TemporalEntity entity) {
        Map<String, Object> payload = Map.of(
                "entityId", entity.id(),
                "text", entity.textRepresentation()
        );
        enqueueVectorAfterCommit(entity.id(), "UPSERT", payload);
    }

    public void enqueueVectorDeleteAfterCommit(String entityId) {
        enqueueVectorAfterCommit(entityId, "DELETE", Map.of("entityId", entityId));
    }

    public void enqueueProcedureTemplateVectorUpsertAfterCommit(String templateId, String triggerIntent) {
        Map<String, Object> payload = Map.of(
                "entityId", templateId,
                "text", triggerIntent
        );
        enqueueAfterCommit("PROCEDURE_TEMPLATE", templateId, "PROCEDURE_TEMPLATE_VECTOR", "UPSERT", payload);
    }

    public void enqueueProcedureTemplateVectorDeleteAfterCommit(String templateId) {
        enqueueAfterCommit(
                "PROCEDURE_TEMPLATE",
                templateId,
                "PROCEDURE_TEMPLATE_VECTOR",
                "DELETE",
                Map.of("entityId", templateId));
    }

    private void enqueueVectorAfterCommit(String entityId,
                                          String operation,
                                          Map<String, Object> payload) {
        enqueueAfterCommit("MEMORY_ENTITY", entityId, "VECTOR", operation, payload);
    }

    private void enqueueAfterCommit(String aggregateType,
                                    String aggregateId,
                                    String projectionType,
                                    String operation,
                                    Map<String, Object> payload) {
        String outboxId = repository.enqueue(
                aggregateType,
                aggregateId,
                projectionType,
                operation,
                payload);
        runAfterCommit(() -> processor.processOne(outboxId));
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
