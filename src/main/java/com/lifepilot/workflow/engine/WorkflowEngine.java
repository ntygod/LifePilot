package com.lifepilot.workflow.engine;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.StepExecutor.WorkflowStepException;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.*;
import com.lifepilot.workflow.model.ErrorStrategy.*;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

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
     * 执行工作流（手动触发或触发器调用）。
     *
     * @param workflowId 工作流定义 ID
     * @param inputs     工作流输入参数
     * @return 执行完成后的工作流实例
     * @throws IllegalArgumentException 工作流定义未找到或已禁用时抛出
     */
    public WorkflowInstance execute(String workflowId, Map<String, Object> inputs) {
        return executeInternal(workflowId, inputs, 0);
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

        Thread.ofVirtual().name("workflow-crash-recovery").start(() -> {
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
        });
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

        while (dagScheduler.hasNext(plan, completedStepIds)) {
            List<WorkflowStep> readySteps = dagScheduler.getReadySteps(plan, completedStepIds);
            if (readySteps.isEmpty()) {
                log.error("DAG 调度异常：hasNext=true 但无就绪步骤: instanceId={}", instance.id());
                return failWorkflow(instance, "DAG 调度异常：无就绪步骤");
            }

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
                List<CompletableFuture<StepResult>> futures = new ArrayList<>();

                for (WorkflowStep step : readySteps) {
                    eventRecorder.record(WorkflowEventType.STEP_STARTED, instance.id(),
                            instance.workflowId(), step.id(),
                            Map.of("stepType", extractStepType(step)));

                    final WorkflowInstance currentInstance = instance;
                    futures.add(CompletableFuture.supplyAsync(() -> {
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
                    }, Thread.ofVirtual().factory()::newThread));
                }

                // 等待所有并发步骤完成
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

                // 检查结果
                for (CompletableFuture<StepResult> future : futures) {
                    StepResult result = future.join();
                    if (result.error() != null) {
                        log.error("并发步骤执行失败: stepId={}, error={}",
                                result.stepId(), result.error().getMessage());
                        return failWorkflow(instance,
                                "并发步骤执行失败: stepId=" + result.stepId());
                    }
                    if (result.instance() != null
                            && result.instance().state() != WorkflowState.RUNNING) {
                        // 非 RUNNING 状态（PAUSED/WAITING/FAILED）
                        return result.instance();
                    }
                    completedStepIds.add(result.stepId());
                }
                // 合并最新 context（并发步骤可能修改了 context）
                instance = instance.toBuilder().updatedAt(Instant.now()).build();
            }

            // Checkpoint：更新 completedStepIds 并持久化
            instance = instance.toBuilder()
                    .completedStepIds(Set.copyOf(completedStepIds))
                    .updatedAt(Instant.now())
                    .build();
            repository.updateInstance(instance);
        }

        // 所有步骤完成 → COMPLETED
        return transition(instance, WorkflowState.COMPLETED);
    }

    /** 并发步骤执行结果。 */
    private record StepResult(String stepId,
                              WorkflowInstance instance,
                              Exception error) {}

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
        try {
            Map<String, Object> output = stepExecutor.execute(step, instance.context(), expressionEngine);

            // 检查 WaitStep 特殊标记
            if ("wait".equals(output.get("__type"))) {
                insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                        attempt, null, toJson(output), null, stepStart);
                return transition(instance, WorkflowState.WAITING);
            }

            // 检查 ApprovalStep 特殊标记
            if ("approval".equals(output.get("__type"))) {
                insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                        attempt, null, toJson(output), null, stepStart);

                // 将审批信息写入 context
                instance.context().set("steps." + step.id() + ".approval", output);
                instance.context().set("steps." + step.id() + ".pausedAt", Instant.now().toString());

                // 设置 pendingApprovalStepId
                instance = instance.toBuilder()
                        .pendingApprovalStepId(step.id())
                        .updatedAt(Instant.now())
                        .build();

                // 记录审计事件
                eventRecorder.record(WorkflowEventType.APPROVAL_REQUESTED, instance.id(),
                        instance.workflowId(), step.id(), output);

                // 转换为 PAUSED 状态
                instance = transition(instance, WorkflowState.PAUSED);
                repository.updateInstance(instance);
                log.info("工作流进入 PAUSED 状态（等待审批）: instanceId={}, stepId={}",
                        instance.id(), step.id());
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

            eventRecorder.record(WorkflowEventType.STEP_COMPLETED, instance.id(),
                    instance.workflowId(), step.id(),
                    Map.of("durationMs", Duration.between(stepStart, Instant.now()).toMillis()));
            return instance.toBuilder().updatedAt(Instant.now()).build();

        } catch (Exception e) {
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
                    Set<String> completed = new HashSet<>(instance.completedStepIds());
                    executeDag(instance, definition.steps(), completed, 0);
                }
                case WAITING -> {
                    log.info("崩溃恢复 WAITING 实例: instanceId={}", instance.id());
                    WorkflowDefinition definition = registry.find(instance.workflowId()).orElse(null);
                    if (definition == null) {
                        markRecoveryFailed(instance, "工作流定义未找到");
                        return;
                    }
                    WorkflowInstance running = transition(instance, WorkflowState.RUNNING);
                    if (running.state() == WorkflowState.RUNNING) {
                        Set<String> completed = new HashSet<>(running.completedStepIds());
                        executeDag(running, definition.steps(), completed, 0);
                    }
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
