package com.lifepilot.workflow.engine;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.StepExecutor.WorkflowStepException;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.*;
import com.lifepilot.workflow.model.ErrorStrategy.Compensate;
import com.lifepilot.workflow.model.ErrorStrategy.Fail;
import com.lifepilot.workflow.model.ErrorStrategy.Retry;
import com.lifepilot.workflow.model.ErrorStrategy.Skip;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 工作流执行引擎 — 负责实例创建、DAG 调度、状态机驱动、步骤分发和错误处理。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建 {@link WorkflowInstance} 并驱动状态机转换</li>
 *   <li>基于 {@link DagScheduler} 进行 DAG 拓扑排序和并行调度</li>
 *   <li>委托 {@link StepExecutor} 执行步骤，按 {@link ErrorStrategy} 分发错误处理</li>
 *   <li>处理 WaitStep（WAITING）、ApprovalStep（PAUSED）和 SubWorkflowStep（递归执行）</li>
 *   <li>通过 {@link WorkflowEventRecorder} 记录审计事件</li>
 *   <li>崩溃恢复：扫描 RUNNING/WAITING/PAUSED 实例并恢复执行</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    // CompletableFuture 需要的是“会启动任务”的 Executor；ThreadFactory#newThread 只会创建线程，不会 start。
    private static final Executor VIRTUAL_THREAD_EXECUTOR = command -> Thread.ofVirtual().start(command);

    private final WorkflowRegistry registry;
    private final StepExecutor stepExecutor;
    private final ExpressionEngine expressionEngine;
    private final WorkflowRepository repository;
    private final WorkflowConfigProperties config;
    private final DagScheduler dagScheduler;
    private final WorkflowEventRecorder eventRecorder;

    public WorkflowEngine(WorkflowRegistry registry,
                          StepExecutor stepExecutor,
                          ExpressionEngine expressionEngine,
                          WorkflowRepository repository,
                          WorkflowConfigProperties config,
                          DagScheduler dagScheduler,
                          WorkflowEventRecorder eventRecorder) {
        this.registry = registry;
        this.stepExecutor = stepExecutor;
        this.expressionEngine = expressionEngine;
        this.repository = repository;
        this.config = config;
        this.dagScheduler = dagScheduler;
        this.eventRecorder = eventRecorder;
    }

    // ==================== 公开 API ====================

    /**
     * 从已创建的实例开始执行 DAG（由 WorkflowRunner 在 Virtual Thread 上调用）。
     *
     * <p>加载实例 → CREATED→RUNNING → executeDag() → 异常时转为 FAILED。
     *
     * @param instanceId 工作流实例 ID
     * @throws IllegalArgumentException 实例或定义未找到时抛出
     */
    public void executeFromInstance(String instanceId) {
        WorkflowInstance instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        String workflowId = instance.workflowId();
        try {
            WorkflowDefinition definition = registry.find(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "工作流定义未找到: workflowId=" + workflowId));

            log.info("开始执行工作流: instanceId={}, workflowId={}, 步骤数={}",
                    instanceId, workflowId, definition.steps().size());

            // CREATED → RUNNING
            instance = transition(instance, WorkflowState.RUNNING);

            // DAG 执行
            executeDag(instance, definition.steps(), new HashSet<>(instance.completedStepIds()), 0);

            log.info("工作流执行完成: instanceId={}, workflowId={}", instanceId, workflowId);
        } catch (Exception e) {
            log.error("工作流执行异常: instanceId={}, error={}", instanceId, e.getMessage(), e);
            failWorkflow(instance, "执行异常: " + e.getMessage());
        }
    }

    /**
     * 从阻塞状态恢复执行（由 WakeupScheduler 触发）。
     *
     * <p>加载实例 → 将 blockedStepId 加入已完成集合 → 清除阻塞字段 →
     * WAITING→RUNNING → 从下一步继续 DAG 执行。
     *
     * @param instanceId 工作流实例 ID
     */
    public void resumeFromBlocked(String instanceId) {
        WorkflowInstance instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        // 记录原始阻塞步骤 ID（清除前保存，用于日志）
        String originalBlockedStepId = instance.blockedStepId();
        String workflowId = instance.workflowId();

        try {
            // 将 blockedStepId 加入已完成集合
            Set<String> completed = new HashSet<>(instance.completedStepIds());
            if (originalBlockedStepId != null) {
                completed.add(originalBlockedStepId);
            }

            // 清除阻塞字段，转换为 RUNNING
            instance = instance.toBuilder()
                    .completedStepIds(Set.copyOf(completed))
                    .wakeUpAt(null)
                    .blockedStepId(null)
                    .blockedReason(null)
                    .updatedAt(Instant.now())
                    .build();
            instance = transition(instance, WorkflowState.RUNNING);

            WorkflowDefinition definition = registry.find(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "工作流定义未找到: workflowId=" + workflowId));

            log.info("从阻塞状态恢复 DAG 执行: instanceId={}, 原阻塞步骤={}",
                    instanceId, originalBlockedStepId);

            executeDag(instance, definition.steps(), completed, 0);
        } catch (Exception e) {
            log.error("恢复阻塞工作流异常: instanceId={}, error={}", instanceId, e.getMessage(), e);
            failWorkflow(instance, "恢复执行异常: " + e.getMessage());
        }
    }



    /**
     * 恢复中断的工作流实例（从 WAITING 状态恢复）。
     *
     * @param instanceId 工作流实例 ID
     * @return 恢复执行后的工作流实例
     * @throws IllegalArgumentException 实例未找到时抛出
     */
    public WorkflowInstance resume(String instanceId) {
        WorkflowInstance instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        WorkflowInstance running = transition(instance, WorkflowState.RUNNING);
        if (running == instance) {
            return instance;
        }

        WorkflowDefinition definition = registry.find(running.workflowId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "工作流定义未找到: workflowId=" + running.workflowId()));

        // 从 completedStepIds 恢复 DAG 调度
        return executeDag(running, definition.steps(),
                new HashSet<>(running.completedStepIds()), 0);
    }

    /**
     * 处理审批决策。
     *
     * @param instanceId 工作流实例 ID
     * @param stepId     审批步骤 ID
     * @param decision   审批决策
     * @return 更新后的实例
     * @throws IllegalStateException 实例不是 PAUSED 状态或 stepId 不匹配时抛出
     */
    public WorkflowInstance approve(String instanceId, String stepId, ApprovalDecision decision) {
        WorkflowInstance instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));

        if (instance.state() != WorkflowState.PAUSED) {
            throw new IllegalStateException(
                    "工作流实例不是 PAUSED 状态: instanceId=" + instanceId + ", state=" + instance.state());
        }
        if (!stepId.equals(instance.pendingApprovalStepId())) {
            throw new IllegalStateException(
                    "审批步骤 ID 不匹配: expected=" + instance.pendingApprovalStepId() + ", actual=" + stepId);
        }

        // 记录审批决策事件
        eventRecorder.record(WorkflowEventType.APPROVAL_DECIDED, instanceId, instance.workflowId(),
                stepId, Map.of("decision", decision.decision().name(), "decidedBy", decision.decidedBy()));

        // 将决策写入 context
        instance.context().set("steps." + stepId + ".approval.decision", decision.decision().name());
        instance.context().set("steps." + stepId + ".approval.decidedBy", decision.decidedBy());
        if (decision.reason() != null) {
            instance.context().set("steps." + stepId + ".approval.reason", decision.reason());
        }

        if (decision.decision() == ApprovalDecision.Decision.APPROVED) {
            // 清除 pendingApprovalStepId，转换 PAUSED→RUNNING
            WorkflowInstance cleared = instance.toBuilder()
                    .pendingApprovalStepId(null)
                    .updatedAt(Instant.now())
                    .build();
            WorkflowInstance running = transition(cleared, WorkflowState.RUNNING);

            // 将审批步骤加入已完成集合
            Set<String> completed = new HashSet<>(running.completedStepIds());
            completed.add(stepId);
            WorkflowInstance updated = running.toBuilder()
                    .completedStepIds(Set.copyOf(completed))
                    .updatedAt(Instant.now())
                    .build();
            repository.updateInstance(updated);

            // 从下一个就绪步骤继续 DAG 执行
            String workflowId = updated.workflowId();
            WorkflowDefinition definition = registry.find(workflowId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "工作流定义未找到: workflowId=" + workflowId));

            log.info("审批通过，恢复 DAG 执行: instanceId={}, stepId={}", instanceId, stepId);
            return executeDag(updated, definition.steps(), completed, 0);
        } else {
            // REJECTED → FAILED
            log.info("审批拒绝，工作流失败: instanceId={}, stepId={}, reason={}",
                    instanceId, stepId, decision.reason());
            return failWorkflow(instance,
                    "审批被拒绝: stepId=" + stepId + ", decidedBy=" + decision.decidedBy());
        }
    }

    /**
     * 取消正在执行的工作流实例。
     *
     * @param instanceId 工作流实例 ID
     * @return 取消后的工作流实例
     * @throws IllegalArgumentException 实例未找到时抛出
     */
    public WorkflowInstance cancel(String instanceId) {
        WorkflowInstance instance = repository.findInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("工作流实例未找到: id=" + instanceId));
        return transition(instance, WorkflowState.CANCELLED);
    }

    /**
     * 崩溃恢复：扫描 RUNNING/WAITING/PAUSED 实例并恢复执行。
     */
    public void recoverInterruptedInstances() {
        if (!config.isCrashRecoveryEnabled()) {
            log.info("崩溃恢复已禁用，跳过");
            return;
        }

        // 同步执行崩溃恢复，确保在触发器注册完成后、cron 触发新实例之前完成
        try {
            log.info("开始崩溃恢复：扫描中断的工作流实例");
            List<WorkflowInstance> interrupted = repository.findInstancesByState(
                    WorkflowState.RUNNING, WorkflowState.WAITING, WorkflowState.PAUSED);

            if (interrupted.isEmpty()) {
                log.info("崩溃恢复完成：无中断实例");
                return;
            }

            log.info("崩溃恢复：发现 {} 个中断实例", interrupted.size());
            for (WorkflowInstance instance : interrupted) {
                recoverInstance(instance);
            }
            log.info("崩溃恢复完成");
        } catch (Exception e) {
            log.error("崩溃恢复过程发生异常", e);
        }
    }

    // ==================== 内部执行逻辑 ====================

    /**
     * 内部执行方法，支持嵌套深度检查（SubWorkflowStep）。
     */
    private WorkflowInstance executeInternal(String workflowId,
                                             Map<String, Object> inputs,
                                             int nestingDepth) {
        if (nestingDepth > config.getMaxNestingDepth()) {
            throw new IllegalStateException(
                    "子工作流嵌套深度超过上限: depth=" + nestingDepth
                    + ", maxNestingDepth=" + config.getMaxNestingDepth());
        }

        WorkflowDefinition definition = registry.find(workflowId)
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

        // CREATED → RUNNING
        instance = transition(instance, WorkflowState.RUNNING);

        // DAG 执行
        return executeDag(instance, definition.steps(), new HashSet<>(), nestingDepth);
    }

    /**
     * DAG 执行循环：拓扑排序 → 并行调度 → checkpoint。
     *
     * @param instance         当前工作流实例
     * @param steps            步骤列表
     * @param completedStepIds 已完成步骤 ID 集合（可变）
     * @param nestingDepth     嵌套深度
     * @return 执行完成后的实例
     */
    private WorkflowInstance executeDag(WorkflowInstance instance,
                                        List<WorkflowStep> steps,
                                        Set<String> completedStepIds,
                                        int nestingDepth) {
        ExecutionPlan plan = dagScheduler.buildExecutionPlan(steps);
        int totalSteps = plan.stepMap().size();
        int round = 0;

        log.info("DAG 执行开始: instanceId={}, 总步骤数={}, 已完成={}",
                instance.id(), totalSteps, completedStepIds.size());

        while (dagScheduler.hasNext(plan, completedStepIds)) {
            round++;
            List<WorkflowStep> readySteps = dagScheduler.getReadySteps(plan, completedStepIds);
            if (readySteps.isEmpty()) {
                log.error("DAG 调度异常：hasNext=true 但无就绪步骤: instanceId={}", instance.id());
                return failWorkflow(instance, "DAG 调度异常：无就绪步骤");
            }

            log.info("DAG 第 {} 轮: instanceId={}, 就绪步骤={}, 已完成={}/{}",
                    round, instance.id(),
                    readySteps.stream().map(s -> s.id() + "(" + extractStepType(s) + ")").toList(),
                    completedStepIds.size(), totalSteps);

            if (readySteps.size() == 1) {
                // 单步直接执行
                WorkflowStep step = readySteps.getFirst();
                eventRecorder.record(WorkflowEventType.STEP_STARTED, instance.id(),
                        instance.workflowId(), step.id(),
                        Map.of("stepType", extractStepType(step)));

                instance = executeStepWithErrorHandling(instance, step, nestingDepth);

                // 检查非 RUNNING 状态（PAUSED/WAITING/FAILED/CANCELLED）
                if (instance.state() != WorkflowState.RUNNING) {
                    return instance;
                }
                completedStepIds.add(step.id());
            } else {
                // 多步 Virtual Thread 并发执行
                Semaphore semaphore = new Semaphore(config.getMaxParallelBranches());
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                CompletionService<StepResult> completionService = new ExecutorCompletionService<>(executor);
                List<Future<StepResult>> futures = new ArrayList<>();

                try {
                    for (WorkflowStep step : readySteps) {
                        eventRecorder.record(WorkflowEventType.STEP_STARTED, instance.id(),
                                instance.workflowId(), step.id(),
                                Map.of("stepType", extractStepType(step)));

                        final WorkflowInstance currentInstance = instance;
                        futures.add(completionService.submit(() -> {
                            try {
                                semaphore.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return new StepResult(step.id(), null,
                                        new WorkflowStepException(step.id(), "并发执行被中断"));
                            }
                            try {
                                WorkflowInstance result = executeStepWithErrorHandling(
                                        currentInstance, step, nestingDepth);
                                return new StepResult(step.id(), result, null);
                            } catch (Exception e) {
                                return new StepResult(step.id(), null, e);
                            } finally {
                                semaphore.release();
                            }
                        }));
                    }

                    List<StepResult> results = new ArrayList<>(readySteps.size());
                    WorkflowInstance nonRunningInstance = null;
                    List<String> failedMessages = new ArrayList<>();

                    for (int i = 0; i < readySteps.size(); i++) {
                        StepResult result;
                        try {
                            result = completionService.take().get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            cancelPendingFutures(futures);
                            return failWorkflow(instance, "并发步骤执行被中断");
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause();
                            Exception exception = cause instanceof Exception ex
                                    ? ex
                                    : new RuntimeException(cause);
                            result = new StepResult("unknown", null, exception);
                        }

                        results.add(result);

                        if (isFailedStepResult(result)) {
                            String failedMessage = buildFailedMessage(result);
                            log.error("并发步骤执行失败: {}", failedMessage);
                            failedMessages.add(failedMessage);
                            cancelPendingFutures(futures);
                            String mergedReason = "并发步骤执行失败: "
                                    + String.join("; ", failedMessages);
                            return failWorkflow(instance, mergedReason);
                        }

                        if (result.instance() != null
                                && result.instance().state() != WorkflowState.RUNNING
                                && nonRunningInstance == null) {
                            // 非 RUNNING 且非 FAILED 状态（PAUSED/WAITING）
                            nonRunningInstance = result.instance();
                        }
                    }

                    // 存在非 RUNNING 状态（PAUSED/WAITING）→ 返回该实例
                    if (nonRunningInstance != null) {
                        return nonRunningInstance;
                    }

                    // 所有步骤都成功 → 将所有 stepId 加入 completedStepIds
                    for (StepResult result : results) {
                        completedStepIds.add(result.stepId());
                    }
                    // 合并最新 context（并发步骤可能修改了 context）
                    instance = instance.toBuilder().updatedAt(Instant.now()).build();
                } finally {
                    cancelPendingFutures(futures);
                    executor.shutdownNow();
                }
            }

            // Checkpoint：更新 completedStepIds 并持久化
            instance = instance.toBuilder()
                    .completedStepIds(Set.copyOf(completedStepIds))
                    .updatedAt(Instant.now())
                    .build();
            repository.updateInstance(instance);
        }

        // 所有步骤完成 → COMPLETED
        log.info("DAG 执行完成: instanceId={}, 总步骤={}, 总轮次={}",
                instance.id(), totalSteps, round);
        return transition(instance, WorkflowState.COMPLETED);
    }

    /** 并发步骤执行结果。 */
    private record StepResult(String stepId,
                              WorkflowInstance instance,
                              Exception error) {}

    private boolean isFailedStepResult(StepResult result) {
        return result.error() != null
                || (result.instance() != null && result.instance().state() == WorkflowState.FAILED);
    }

    private String buildFailedMessage(StepResult result) {
        if (result.error() != null) {
            return "stepId=" + result.stepId() + ": " + result.error().getMessage();
        }
        String reason = result.instance() != null && result.instance().failureReason() != null
                ? result.instance().failureReason()
                : "未知原因";
        return "stepId=" + result.stepId() + ": " + reason;
    }

    private void cancelPendingFutures(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private boolean isStepCancellation(Exception e) {
        return Thread.currentThread().isInterrupted()
                || "步骤执行被中断".equals(e.getMessage());
    }

    /**
     * 执行单个步骤，包含错误处理逻辑。
     */
    private WorkflowInstance executeStepWithErrorHandling(WorkflowInstance instance,
                                                          WorkflowStep step,
                                                          int nestingDepth) {
        String stepType = extractStepType(step);
        ErrorStrategy strategy = step.errorStrategy();
        if (strategy == null) {
            strategy = new Fail();
        }

        return switch (strategy) {
            case Retry retry -> executeWithRetry(instance, step, stepType, retry, nestingDepth);
            case Skip skip -> executeWithSkip(instance, step, stepType, skip, nestingDepth);
            case Fail _ -> executeWithFail(instance, step, stepType, nestingDepth);
            case Compensate compensate -> executeWithCompensate(instance, step, stepType, compensate, nestingDepth);
        };
    }

    /**
     * 执行步骤核心逻辑（成功/WaitStep/ApprovalStep/SubWorkflowStep）。
     */
    private WorkflowInstance executeStepCore(WorkflowInstance instance,
                                             WorkflowStep step,
                                             String stepType,
                                             int attempt,
                                             int nestingDepth) {
        Instant stepStart = Instant.now();
        log.info("步骤开始执行: instanceId={}, stepId={}, stepType={}, attempt={}",
                instance.id(), step.id(), stepType, attempt);
        try {
            // 步骤级超时保护：包装在 CompletableFuture 中，超时后抛出 TimeoutException
            Map<String, Object> output;
            final WorkflowInstance currentInstance = instance;
            CompletableFuture<Map<String, Object>> stepFuture = CompletableFuture.supplyAsync(
                    () -> stepExecutor.execute(step, currentInstance.context(), expressionEngine),
                    VIRTUAL_THREAD_EXECUTOR
            );
            try {
                output = stepFuture.get(config.getDefaultStepTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                stepFuture.cancel(true);
                throw new WorkflowStepException(step.id(),
                        "步骤执行超时: timeout=" + config.getDefaultStepTimeoutSeconds() + "s", e);
            } catch (InterruptedException e) {
                stepFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new WorkflowStepException(step.id(), "步骤执行被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
            }

            // 检查 WaitStep 特殊标记
            if ("wait".equals(output.get("__type"))) {
                int durationSeconds = ((Number) output.get("durationSeconds")).intValue();
                insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                        attempt, null, toJson(output), null, stepStart);

                // 持久化唤醒信息
                Instant wakeUpAt = Instant.now().plusSeconds(durationSeconds);
                instance = instance.toBuilder()
                        .wakeUpAt(wakeUpAt)
                        .blockedStepId(step.id())
                        .blockedReason("wait:" + durationSeconds + "s")
                        .updatedAt(Instant.now())
                        .build();
                instance = transition(instance, WorkflowState.WAITING);
                log.info("工作流进入 WAITING 状态: instanceId={}, stepId={}, wakeUpAt={}",
                        instance.id(), step.id(), wakeUpAt);
                return instance;
            }

            // 检查 ApprovalStep 特殊标记
            if ("approval".equals(output.get("__type"))) {
                int timeoutSeconds = ((Number) output.get("timeoutSeconds")).intValue();
                boolean autoApproveOnTimeout = (boolean) output.get("autoApproveOnTimeout");
                insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                        attempt, null, toJson(output), null, stepStart);

                // 将审批信息写入 context
                instance.context().set("steps." + step.id() + ".approval", output);
                instance.context().set("steps." + step.id() + ".pausedAt", Instant.now().toString());

                // 持久化超时信息和审批步骤 ID
                Instant wakeUpAt = Instant.now().plusSeconds(timeoutSeconds);
                instance = instance.toBuilder()
                        .pendingApprovalStepId(step.id())
                        .wakeUpAt(wakeUpAt)
                        .blockedStepId(step.id())
                        .blockedReason("approval:timeout=" + timeoutSeconds + "s,autoApprove=" + autoApproveOnTimeout)
                        .updatedAt(Instant.now())
                        .build();

                // 记录审计事件
                eventRecorder.record(WorkflowEventType.APPROVAL_REQUESTED, instance.id(),
                        instance.workflowId(), step.id(), output);

                // 转换为 PAUSED 状态
                instance = transition(instance, WorkflowState.PAUSED);
                log.info("工作流进入 PAUSED 状态（等待审批）: instanceId={}, stepId={}, wakeUpAt={}",
                        instance.id(), step.id(), wakeUpAt);
                return instance;
            }

            // 检查 SubWorkflowStep 特殊标记
            if ("sub-workflow".equals(output.get("__type"))) {
                String subWorkflowId = (String) output.get("workflowId");
                @SuppressWarnings("unchecked")
                Map<String, Object> subParams = (Map<String, Object>) output.get("params");

                log.info("执行子工作流: parentInstanceId={}, subWorkflowId={}, nestingDepth={}",
                        instance.id(), subWorkflowId, nestingDepth + 1);

                WorkflowInstance subInstance = executeInternal(subWorkflowId, subParams, nestingDepth + 1);

                Map<String, Object> subOutput = Map.of(
                        "instanceId", subInstance.id(),
                        "state", subInstance.state().name()
                );
                instance.context().set("steps." + step.id() + ".output", subOutput);

                StepState subStepState = subInstance.state() == WorkflowState.COMPLETED
                        ? StepState.COMPLETED : StepState.FAILED;
                insertStepLog(instance.id(), step.id(), stepType, subStepState,
                        attempt, null, toJson(subOutput), null, stepStart);

                if (subInstance.state() != WorkflowState.COMPLETED) {
                    throw new WorkflowStepException(step.id(),
                            "子工作流执行失败: subWorkflowId=" + subWorkflowId
                            + ", state=" + subInstance.state());
                }

                eventRecorder.record(WorkflowEventType.STEP_COMPLETED, instance.id(),
                        instance.workflowId(), step.id(),
                        Map.of("durationMs", Duration.between(stepStart, Instant.now()).toMillis()));
                return instance.toBuilder().updatedAt(Instant.now()).build();
            }

            // 普通步骤：存储输出到上下文
            instance.context().set("steps." + step.id() + ".output", output);
            insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                    attempt, null, toJson(output), null, stepStart);

            long durationMs = Duration.between(stepStart, Instant.now()).toMillis();
            log.info("步骤执行成功: instanceId={}, stepId={}, stepType={}, 耗时={}ms",
                    instance.id(), step.id(), stepType, durationMs);

            eventRecorder.record(WorkflowEventType.STEP_COMPLETED, instance.id(),
                    instance.workflowId(), step.id(),
                    Map.of("durationMs", Duration.between(stepStart, Instant.now()).toMillis()));
            return instance.toBuilder().updatedAt(Instant.now()).build();

        } catch (Exception e) {
            if (isStepCancellation(e)) {
                log.info("步骤执行已取消: instanceId={}, stepId={}, stepType={}",
                        instance.id(), step.id(), stepType);
                throw e instanceof WorkflowStepException wse ? wse
                        : new WorkflowStepException(step.id(), e.getMessage(), e);
            }

            long durationMs = Duration.between(stepStart, Instant.now()).toMillis();
            log.error("步骤执行失败: instanceId={}, stepId={}, stepType={}, 耗时={}ms, error={}",
                    instance.id(), step.id(), stepType, durationMs, e.getMessage());
            instance.context().set("steps." + step.id() + ".error", e.getMessage());
            insertStepLog(instance.id(), step.id(), stepType, StepState.FAILED,
                    attempt, null, null, e.getMessage(), stepStart);

            eventRecorder.record(WorkflowEventType.STEP_FAILED, instance.id(),
                    instance.workflowId(), step.id(),
                    Map.of("errorMessage", e.getMessage() != null ? e.getMessage() : "unknown",
                           "errorStrategy", extractStepType(step)));

            throw e instanceof WorkflowStepException wse ? wse
                    : new WorkflowStepException(step.id(), e.getMessage(), e);
        }
    }

    // ==================== 错误策略分发 ====================

    private WorkflowInstance executeWithRetry(WorkflowInstance instance,
                                              WorkflowStep step, String stepType,
                                              Retry retry, int nestingDepth) {
        int maxAttempts = retry.maxAttempts();
        long initialDelay = retry.initialDelayMs();
        long maxDelay = retry.maxDelayMs();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeStepCore(instance, step, stepType, attempt, nestingDepth);
            } catch (Exception e) {
                if (isStepCancellation(e)) {
                    throw e instanceof WorkflowStepException wse ? wse
                            : new WorkflowStepException(step.id(), e.getMessage(), e);
                }
                log.warn("步骤执行失败（重试 {}/{}）: stepId={}, error={}",
                        attempt, maxAttempts, step.id(), e.getMessage());
                if (attempt < maxAttempts) {
                    long delay = Math.min(initialDelay * (long) Math.pow(2, attempt - 1), maxDelay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return failWorkflow(instance, "步骤重试耗尽: stepId=" + step.id());
    }

    private WorkflowInstance executeWithSkip(WorkflowInstance instance,
                                             WorkflowStep step, String stepType,
                                             Skip skip, int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            if (isStepCancellation(e)) {
                throw e instanceof WorkflowStepException wse ? wse
                        : new WorkflowStepException(step.id(), e.getMessage(), e);
            }
            log.warn("步骤执行失败，Skip 跳过: stepId={}, reason={}", step.id(), skip.reason());
            insertStepLog(instance.id(), step.id(), stepType, StepState.SKIPPED,
                    1, null, null, "Skip: " + skip.reason() + " | " + e.getMessage(),
                    Instant.now());
            eventRecorder.record(WorkflowEventType.STEP_SKIPPED, instance.id(),
                    instance.workflowId(), step.id(),
                    Map.of("reason", skip.reason()));
            return instance.toBuilder().updatedAt(Instant.now()).build();
        }
    }

    private WorkflowInstance executeWithFail(WorkflowInstance instance,
                                             WorkflowStep step, String stepType,
                                             int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            if (isStepCancellation(e)) {
                throw e instanceof WorkflowStepException wse ? wse
                        : new WorkflowStepException(step.id(), e.getMessage(), e);
            }
            log.error("步骤执行失败，Fail 终止工作流: stepId={}, error={}", step.id(), e.getMessage());
            return failWorkflow(instance, "步骤执行失败: stepId=" + step.id() + ", error=" + e.getMessage());
        }
    }

    private WorkflowInstance executeWithCompensate(WorkflowInstance instance,
                                                   WorkflowStep step, String stepType,
                                                   ErrorStrategy.Compensate compensate,
                                                   int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            if (isStepCancellation(e)) {
                throw e instanceof WorkflowStepException wse ? wse
                        : new WorkflowStepException(step.id(), e.getMessage(), e);
            }
            log.warn("步骤执行失败，执行补偿步骤: stepId={}, error={}", step.id(), e.getMessage());
            try {
                WorkflowStep compStep = compensate.compensationStep();
                String compType = extractStepType(compStep);
                Map<String, Object> compOutput = stepExecutor.execute(
                        compStep, instance.context(), expressionEngine);
                instance.context().set("steps." + compStep.id() + ".output", compOutput);
                insertStepLog(instance.id(), compStep.id(), compType, StepState.COMPLETED,
                        1, null, toJson(compOutput), null, Instant.now());
                log.info("补偿步骤执行成功: compStepId={}", compStep.id());
            } catch (Exception compEx) {
                log.error("补偿步骤执行失败: stepId={}, compError={}", step.id(), compEx.getMessage());
            }
            return failWorkflow(instance,
                    "步骤执行失败（已补偿）: stepId=" + step.id() + ", error=" + e.getMessage());
        }
    }

    // ==================== 状态机 ====================

    /**
     * 状态机转换 — 验证转换合法性，更新实例状态并持久化。
     */
    private WorkflowInstance transition(WorkflowInstance instance, WorkflowState to) {
        WorkflowState from = instance.state();
        if (from == to) {
            return instance;
        }
        if (!isValidTransition(from, to)) {
            log.warn("非法状态转换: instanceId={}, from={}, to={}", instance.id(), from, to);
            return instance;
        }

        // 记录状态变更审计事件
        eventRecorder.record(WorkflowEventType.INSTANCE_STATE_CHANGED, instance.id(),
                instance.workflowId(), null,
                Map.of("oldState", from.name(), "newState", to.name()));

        Instant now = Instant.now();
        var builder = instance.toBuilder().state(to).updatedAt(now);

        // 设置时间戳
        if (to == WorkflowState.RUNNING && instance.startedAt() == null) {
            builder.startedAt(now);
        }
        if (to == WorkflowState.COMPLETED || to == WorkflowState.FAILED || to == WorkflowState.CANCELLED) {
            builder.completedAt(now);
        }

        WorkflowInstance updated = builder.build();
        repository.updateInstance(updated);
        log.info("工作流状态转换: instanceId={}, {} → {}", instance.id(), from, to);
        return updated;
    }

    /**
     * 验证状态转换是否合法。
     */
    private boolean isValidTransition(WorkflowState from, WorkflowState to) {
        return switch (from) {
            case CREATED -> to == WorkflowState.RUNNING;
            case RUNNING -> to == WorkflowState.COMPLETED || to == WorkflowState.FAILED
                            || to == WorkflowState.CANCELLED || to == WorkflowState.WAITING
                            || to == WorkflowState.PAUSED;
            case PAUSED -> to == WorkflowState.RUNNING || to == WorkflowState.FAILED;
            case WAITING -> to == WorkflowState.RUNNING;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    /**
     * 将工作流标记为失败。
     */
    private WorkflowInstance failWorkflow(WorkflowInstance instance, String reason) {
        log.error("工作流执行失败: instanceId={}, reason={}", instance.id(), reason);
        WorkflowInstance failed = instance.toBuilder()
                .failureReason(reason)
                .updatedAt(Instant.now())
                .build();
        return transition(failed, WorkflowState.FAILED);
    }

    /**
     * 崩溃恢复单个实例。
     */
    private void recoverInstance(WorkflowInstance instance) {
        try {
            switch (instance.state()) {
                case RUNNING -> {
                    log.info("崩溃恢复 RUNNING 实例: instanceId={}", instance.id());
                    WorkflowDefinition definition = registry.find(instance.workflowId()).orElse(null);
                    if (definition == null) {
                        markRecoveryFailed(instance, "工作流定义未找到");
                        return;
                    }
                    // 预检资源可用性，避免执行到步骤时才失败
                    List<String> missingResources = registry.checkResourceAvailability(definition);
                    if (!missingResources.isEmpty()) {
                        markRecoveryFailed(instance,
                                "工作流定义引用了不可用的资源: " + String.join(", ", missingResources));
                        return;
                    }
                    Set<String> completed = new HashSet<>(instance.completedStepIds());
                    executeDag(instance, definition.steps(), completed, 0);
                }
                case WAITING -> {
                    log.info("崩溃恢复 WAITING 实例: instanceId={}, blockedStepId={}",
                            instance.id(), instance.blockedStepId());
                    // 复用 resumeFromBlocked：清除阻塞字段、将 blockedStepId 加入已完成集合、从下一步继续
                    resumeFromBlocked(instance.id());
                }
                case PAUSED -> {
                    log.info("崩溃恢复 PAUSED 实例: instanceId={}", instance.id());
                    String pendingStepId = instance.pendingApprovalStepId();
                    if (pendingStepId == null) {
                        markRecoveryFailed(instance, "PAUSED 实例缺少 pendingApprovalStepId");
                        return;
                    }

                    // 从 context 读取 pausedAt 时间戳
                    Object pausedAtObj = instance.context().get("steps." + pendingStepId + ".pausedAt");
                    if (pausedAtObj == null) {
                        // 无法判断超时，保持 PAUSED
                        log.info("PAUSED 实例无 pausedAt 信息，保持 PAUSED: instanceId={}", instance.id());
                        return;
                    }

                    Instant pausedAt = Instant.parse(pausedAtObj.toString());
                    // 查找 ApprovalStep 的超时配置
                    WorkflowDefinition definition = registry.find(instance.workflowId()).orElse(null);
                    if (definition == null) {
                        markRecoveryFailed(instance, "工作流定义未找到");
                        return;
                    }

                    int timeoutSeconds = config.getApproval().getDefaultTimeoutSeconds();
                    boolean autoApprove = config.getApproval().isAutoApproveOnTimeout();

                    // 尝试从步骤定义获取超时配置
                    for (WorkflowStep step : definition.steps()) {
                        if (step.id().equals(pendingStepId)
                                && step instanceof WorkflowStep.ApprovalStep approval) {
                            timeoutSeconds = approval.timeoutSeconds();
                            autoApprove = approval.autoApproveOnTimeout();
                            break;
                        }
                    }

                    Duration elapsed = Duration.between(pausedAt, Instant.now());
                    if (elapsed.getSeconds() < timeoutSeconds) {
                        // 未超时，保持 PAUSED
                        log.info("PAUSED 实例未超时，保持 PAUSED: instanceId={}, elapsed={}s, timeout={}s",
                                instance.id(), elapsed.getSeconds(), timeoutSeconds);
                        return;
                    }

                    // 已超时
                    if (autoApprove) {
                        log.info("PAUSED 实例超时，自动批准: instanceId={}, stepId={}",
                                instance.id(), pendingStepId);
                        ApprovalDecision autoDecision = new ApprovalDecision(
                                ApprovalDecision.Decision.APPROVED,
                                "system-auto-approve",
                                "审批超时自动批准",
                                Instant.now()
                        );
                        approve(instance.id(), pendingStepId, autoDecision);
                    } else {
                        log.info("PAUSED 实例超时，标记失败: instanceId={}, stepId={}",
                                instance.id(), pendingStepId);
                        failWorkflow(instance,
                                "审批超时: stepId=" + pendingStepId + ", timeout=" + timeoutSeconds + "s");
                    }
                }
                default -> log.warn("崩溃恢复跳过非预期状态: instanceId={}, state={}",
                        instance.id(), instance.state());
            }
        } catch (Exception e) {
            log.error("崩溃恢复实例失败: instanceId={}, error={}", instance.id(), e.getMessage(), e);
            markRecoveryFailed(instance, e.getMessage());
        }
    }

    /**
     * 标记恢复失败的实例为 FAILED。
     */
    private void markRecoveryFailed(WorkflowInstance instance, String reason) {
        try {
            failWorkflow(instance, "崩溃恢复失败: " + reason);
        } catch (Exception e) {
            log.error("标记恢复失败也失败了: instanceId={}, error={}", instance.id(), e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 提取步骤类型名称。
     */
    private String extractStepType(WorkflowStep step) {
        return switch (step) {
            case WorkflowStep.SkillStep _ -> "skill";
            case WorkflowStep.ToolStep _ -> "tool";
            case WorkflowStep.LlmStep _ -> "llm";
            case WorkflowStep.ConditionStep _ -> "condition";
            case WorkflowStep.LoopStep _ -> "loop";
            case WorkflowStep.ParallelStep _ -> "parallel";
            case WorkflowStep.SubWorkflowStep _ -> "sub-workflow";
            case WorkflowStep.NoopStep _ -> "noop";
            case WorkflowStep.WaitStep _ -> "wait";
            case WorkflowStep.ApprovalStep _ -> "approval";
            case WorkflowStep.NotifyStep _ -> "notify";
        };
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    @org.springframework.lang.Nullable
    private String toJson(@org.springframework.lang.Nullable Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(data);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }

    /**
     * 插入步骤执行日志。
     */
    private void insertStepLog(String instanceId, String stepId, String stepType,
                                StepState state, int attempt,
                                @org.springframework.lang.Nullable String inputJson,
                                @org.springframework.lang.Nullable String outputJson,
                                @org.springframework.lang.Nullable String errorMessage,
                                Instant startedAt) {
        try {
            Instant now = Instant.now();
            long durationMs = Duration.between(startedAt, now).toMillis();
            StepLog stepLog = new StepLog(
                    UUID.randomUUID().toString(),
                    instanceId, stepId, stepType, state, attempt,
                    inputJson, outputJson, errorMessage,
                    startedAt, now, durationMs, now
            );
            repository.insertStepLog(stepLog);
        } catch (Exception e) {
            log.warn("步骤日志写入失败: instanceId={}, stepId={}, error={}",
                    instanceId, stepId, e.getMessage());
        }
    }
}
