package com.lifepilot.workflow.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowEventType;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;

/**
 * 工作流命令服务 — 异步非阻塞的工作流操作入口。
 *
 * <p>替代原有的 {@link WorkflowEngine#execute} 同步阻塞调用，
 * 提供 start / resume / cancel / getStatus 四个命令。
 * start 和 resume 通过 {@link WorkflowRunner} 在 Virtual Thread 上异步执行，
 * 调用方立即获得 instanceId 而不阻塞。
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WorkflowCommandService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCommandService.class);

    private final WorkflowRegistry registry;
    private final WorkflowRepository repository;
    private final WorkflowRunner runner;
    private final WorkflowEventRecorder eventRecorder;

    public WorkflowCommandService(WorkflowRegistry registry,
                                  WorkflowRepository repository,
                                  WorkflowRunner runner,
                                  WorkflowEventRecorder eventRecorder) {
        this.registry = registry;
        this.repository = repository;
        this.runner = runner;
        this.eventRecorder = eventRecorder;
    }

    /**
     * 启动工作流：创建实例（CREATED）→ 持久化 → 提交异步执行 → 返回 instanceId。
     *
     * @param workflowId 工作流定义 ID
     * @param inputs     输入参数（可空）
     * @return 实例 ID（立即返回）
     */
    public String start(String workflowId, @Nullable Map<String, Object> inputs) {
        var definition = registry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("工作流定义未找到: workflowId=" + workflowId));

        if (!definition.enabled()) {
            throw new IllegalArgumentException("工作流已禁用: workflowId=" + workflowId);
        }

        // 创建实例（CREATED 状态）
        Instant now = Instant.now();
        WorkflowContext context = new WorkflowContext();
        if (inputs != null) {
            context.set("inputs", inputs);
        }

        WorkflowInstance instance = WorkflowInstance.builder()
                .id(UUID.randomUUID().toString())
                .workflowId(workflowId)
                .state(WorkflowState.CREATED)
                .context(context)
                .completedStepIds(Set.of())
                .pendingApprovalStepId(null)
                .createdAt(now)
                .updatedAt(now)
                .build();

        repository.saveInstance(instance);
        log.info("工作流实例创建: instanceId={}, workflowId={}", instance.id(), workflowId);

        // 记录审计事件
        eventRecorder.record(WorkflowEventType.INSTANCE_CREATED, instance.id(), workflowId,
                null, Map.of("workflowId", workflowId));

        // 提交异步执行
        runner.submitAsync(instance.id());

        return instance.id();
    }

    /**
     * 恢复工作流：提交异步恢复执行。
     *
     * @param instanceId 实例 ID
     */
    public void resume(String instanceId) {
        var instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        if (instance.state() != WorkflowState.WAITING && instance.state() != WorkflowState.PAUSED) {
            throw new IllegalStateException(
                    "实例状态不允许恢复: id=" + instanceId + ", state=" + instance.state());
        }

        log.info("提交异步恢复: instanceId={}, state={}", instanceId, instance.state());
        runner.submitAsyncResume(instanceId);
    }

    /**
     * 取消工作流。
     *
     * @param instanceId 实例 ID
     */
    public void cancel(String instanceId) {
        var instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        var state = instance.state();
        if (state == WorkflowState.COMPLETED || state == WorkflowState.FAILED
                || state == WorkflowState.CANCELLED) {
            throw new IllegalStateException(
                    "实例已处于终态，无法取消: id=" + instanceId + ", state=" + state);
        }

        var cancelled = instance.toBuilder()
                .state(WorkflowState.CANCELLED)
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .wakeUpAt(null)
                .blockedStepId(null)
                .blockedReason(null)
                .build();

        repository.updateInstance(cancelled);
        log.info("工作流已取消: instanceId={}", instanceId);

        eventRecorder.record(WorkflowEventType.INSTANCE_STATE_CHANGED, instanceId,
                instance.workflowId(), null,
                Map.of("from", instance.state().name(), "to", WorkflowState.CANCELLED.name()));
    }

    /**
     * 查询实例状态。
     *
     * @param instanceId 实例 ID
     * @return 实例 Optional
     */
    public Optional<WorkflowInstance> getStatus(String instanceId) {
        return repository.findInstance(instanceId);
    }
}
