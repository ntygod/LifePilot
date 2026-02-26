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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流执行引擎 — 负责实例创建、状态机驱动、步骤分发和错误处理。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建 {@link WorkflowInstance} 并驱动状态机转换</li>
 *   <li>遍历 {@link WorkflowDefinition#steps()} 并委托 {@link StepExecutor} 执行</li>
 *   <li>按 {@link ErrorStrategy} 分发错误处理（Retry / Skip / Fail / Compensate）</li>
 *   <li>处理 WaitStep（转换为 WAITING 状态）和 SubWorkflowStep（递归执行）</li>
 *   <li>每次状态转换后持久化到 SQLite</li>
 *   <li>崩溃恢复：扫描 RUNNING/WAITING 实例并恢复执行</li>
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

    public WorkflowEngine(WorkflowRegistry registry,
                          StepExecutor stepExecutor,
                          ExpressionEngine expressionEngine,
                          WorkflowRepository repository,
                          WorkflowConfigProperties config) {
        this.registry = registry;
        this.stepExecutor = stepExecutor;
        this.expressionEngine = expressionEngine;
        this.repository = repository;
        this.config = config;
    }

    // ==================== 公开 API ====================

    /**
     * 执行工作流（手动触发或触发器调用）。
     *
     * <p>流程：查找定义 → 创建实例(CREATED) → 转换为 RUNNING → 遍历步骤 → 终态。
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

        // 仅 WAITING 状态可恢复
        WorkflowInstance running = transition(instance, WorkflowState.RUNNING);
        if (running == instance) {
            // 转换被拒绝，返回当前实例
            return instance;
        }

        // 查找工作流定义
        WorkflowDefinition definition = registry.find(running.workflowId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "工作流定义未找到: workflowId=" + running.workflowId()));

        // 从 currentStepIndex + 1 继续执行（WaitStep 已完成，执行下一步）
        return executeSteps(running, definition.steps(), running.currentStepIndex() + 1, 0);
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
     * 崩溃恢复：扫描 RUNNING/WAITING 实例并恢复执行。
     *
     * <p>使用 Virtual Thread 执行，避免阻塞主启动序列。
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
                        WorkflowState.RUNNING, WorkflowState.WAITING);

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
        // 嵌套深度检查
        if (nestingDepth > config.getMaxNestingDepth()) {
            throw new IllegalStateException(
                    "子工作流嵌套深度超过上限: depth=" + nestingDepth
                    + ", maxNestingDepth=" + config.getMaxNestingDepth());
        }

        // 1. 查找工作流定义
        WorkflowDefinition definition = registry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("工作流定义未找到: workflowId=" + workflowId));

        if (!definition.enabled()) {
            throw new IllegalArgumentException("工作流已禁用: workflowId=" + workflowId);
        }

        // 2. 创建实例（CREATED 状态）
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
                .currentStepIndex(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        repository.saveInstance(instance);
        log.info("工作流实例创建: instanceId={}, workflowId={}", instance.id(), workflowId);

        // 3. CREATED → RUNNING
        instance = transition(instance, WorkflowState.RUNNING);

        // 4. 遍历步骤执行
        return executeSteps(instance, definition.steps(), 0, nestingDepth);
    }

    /**
     * 从指定索引开始遍历执行步骤列表。
     */
    private WorkflowInstance executeSteps(WorkflowInstance instance,
                                          List<WorkflowStep> steps,
                                          int startIndex,
                                          int nestingDepth) {
        for (int i = startIndex; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);

            // 更新 currentStepIndex 并持久化
            instance = instance.toBuilder()
                    .currentStepIndex(i)
                    .updatedAt(Instant.now())
                    .build();
            repository.updateInstance(instance);

            // 执行步骤（含错误处理）
            instance = executeStepWithErrorHandling(instance, step, i, nestingDepth);

            // 检查是否进入 WAITING 状态（WaitStep）
            if (instance.state() == WorkflowState.WAITING) {
                log.info("工作流进入 WAITING 状态: instanceId={}, stepIndex={}", instance.id(), i);
                return instance;
            }

            // 检查是否已失败或取消
            if (instance.state() == WorkflowState.FAILED
                    || instance.state() == WorkflowState.CANCELLED) {
                return instance;
            }
        }

        // 所有步骤执行完成 → COMPLETED
        instance = transition(instance, WorkflowState.COMPLETED);
        return instance;
    }

    /**
     * 执行单个步骤，包含错误处理逻辑。
     */
    private WorkflowInstance executeStepWithErrorHandling(WorkflowInstance instance,
                                                          WorkflowStep step,
                                                          int stepIndex,
                                                          int nestingDepth) {
        String stepType = extractStepType(step);
        ErrorStrategy strategy = step.errorStrategy() != null
                ? step.errorStrategy()
                : new Fail(); // 无策略时默认 Fail

        return switch (strategy) {
            case Retry retry -> executeWithRetry(instance, step, stepType, retry, nestingDepth);
            case Skip skip -> executeWithSkip(instance, step, stepType, skip, nestingDepth);
            case Fail _ -> executeWithFail(instance, step, stepType, nestingDepth);
            case Compensate compensate -> executeWithCompensate(instance, step, stepType, compensate, nestingDepth);
        };
    }

    /**
     * 执行步骤并处理结果（成功/WaitStep/SubWorkflowStep）。
     *
     * @return 更新后的实例，或在失败时抛出异常
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
                // 记录 StepLog（COMPLETED 状态，WaitStep 本身执行成功）
                insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                        attempt, null, toJson(output), null, stepStart);

                // 转换为 WAITING 状态
                return transition(instance, WorkflowState.WAITING);
            }

            // 检查 SubWorkflowStep 特殊标记
            if ("sub-workflow".equals(output.get("__type"))) {
                String subWorkflowId = (String) output.get("workflowId");
                @SuppressWarnings("unchecked")
                Map<String, Object> subParams = (Map<String, Object>) output.get("params");

                log.info("执行子工作流: parentInstanceId={}, subWorkflowId={}, nestingDepth={}",
                        instance.id(), subWorkflowId, nestingDepth + 1);

                // 递归执行子工作流
                WorkflowInstance subInstance = executeInternal(subWorkflowId, subParams, nestingDepth + 1);

                // 将子工作流结果存入上下文
                Map<String, Object> subOutput = Map.of(
                        "instanceId", subInstance.id(),
                        "state", subInstance.state().name()
                );
                instance.context().set("steps." + step.id() + ".output", subOutput);

                // 记录 StepLog
                StepState subStepState = subInstance.state() == WorkflowState.COMPLETED
                        ? StepState.COMPLETED : StepState.FAILED;
                insertStepLog(instance.id(), step.id(), stepType, subStepState,
                        attempt, null, toJson(subOutput), null, stepStart);

                if (subInstance.state() != WorkflowState.COMPLETED) {
                    throw new WorkflowStepException(step.id(),
                            "子工作流执行失败: subWorkflowId=" + subWorkflowId
                            + ", state=" + subInstance.state());
                }

                return instance.toBuilder().updatedAt(Instant.now()).build();
            }

            // 普通步骤：存储输出到上下文
            instance.context().set("steps." + step.id() + ".output", output);

            // 记录 StepLog
            insertStepLog(instance.id(), step.id(), stepType, StepState.COMPLETED,
                    attempt, null, toJson(output), null, stepStart);

            return instance.toBuilder().updatedAt(Instant.now()).build();

        } catch (Exception e) {
            // 存储错误到上下文
            instance.context().set("steps." + step.id() + ".error", e.getMessage());

            // 记录失败 StepLog
            insertStepLog(instance.id(), step.id(), stepType, StepState.FAILED,
                    attempt, null, null, e.getMessage(), stepStart);

            throw e instanceof WorkflowStepException wse ? wse
                    : new WorkflowStepException(step.id(), e.getMessage(), e);
        }
    }

    // ==================== 错误策略分发 ====================

    /**
     * Retry 策略：指数退避重试，耗尽后回退到 Fail。
     */
    private WorkflowInstance executeWithRetry(WorkflowInstance instance,
                                              WorkflowStep step,
                                              String stepType,
                                              Retry retry,
                                              int nestingDepth) {
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
                    // 指数退避等待
                    long delay = Math.min(initialDelay * (long) Math.pow(2, attempt - 1), maxDelay);
                    log.debug("重试等待: stepId={}, delayMs={}", step.id(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断: stepId={}", step.id());
                        break;
                    }
                }
            }
        }

        // 重试耗尽，回退到 Fail
        log.warn("重试耗尽，回退到 Fail 策略: stepId={}, maxAttempts={}", step.id(), maxAttempts);
        return failWorkflow(instance, "步骤重试耗尽: stepId=" + step.id());
    }

    /**
     * Skip 策略：标记步骤为 SKIPPED，继续执行。
     */
    private WorkflowInstance executeWithSkip(WorkflowInstance instance,
                                             WorkflowStep step,
                                             String stepType,
                                             Skip skip,
                                             int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            log.warn("步骤执行失败，Skip 跳过: stepId={}, reason={}, error={}",
                    step.id(), skip.reason(), e.getMessage());

            // 记录 SKIPPED StepLog
            insertStepLog(instance.id(), step.id(), stepType, StepState.SKIPPED,
                    1, null, null, "Skip: " + skip.reason() + " | " + e.getMessage(),
                    Instant.now());

            return instance.toBuilder().updatedAt(Instant.now()).build();
        }
    }

    /**
     * Fail 策略：步骤失败时终止工作流。
     */
    private WorkflowInstance executeWithFail(WorkflowInstance instance,
                                             WorkflowStep step,
                                             String stepType,
                                             int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            log.error("步骤执行失败，Fail 终止工作流: stepId={}, error={}", step.id(), e.getMessage());
            return failWorkflow(instance, "步骤执行失败: stepId=" + step.id() + ", error=" + e.getMessage());
        }
    }

    /**
     * Compensate 策略：执行补偿步骤后标记失败。
     */
    private WorkflowInstance executeWithCompensate(WorkflowInstance instance,
                                                    WorkflowStep step,
                                                    String stepType,
                                                    Compensate compensate,
                                                    int nestingDepth) {
        try {
            return executeStepCore(instance, step, stepType, 1, nestingDepth);
        } catch (Exception e) {
            log.warn("步骤执行失败，执行补偿步骤: stepId={}, compensationStepId={}",
                    step.id(), compensate.compensationStep().id());

            // 执行补偿步骤
            try {
                WorkflowStep compStep = compensate.compensationStep();
                String compStepType = extractStepType(compStep);
                Instant compStart = Instant.now();

                Map<String, Object> compOutput = stepExecutor.execute(
                        compStep, instance.context(), expressionEngine);
                instance.context().set("steps." + compStep.id() + ".output", compOutput);

                insertStepLog(instance.id(), compStep.id(), compStepType, StepState.COMPLETED,
                        1, null, toJson(compOutput), null, compStart);

                log.info("补偿步骤执行成功: compensationStepId={}", compStep.id());
            } catch (Exception compEx) {
                log.error("补偿步骤执行失败: compensationStepId={}, error={}",
                        compensate.compensationStep().id(), compEx.getMessage());
            }

            // 补偿后仍标记工作流为 FAILED
            return failWorkflow(instance,
                    "步骤执行失败（已补偿）: stepId=" + step.id() + ", error=" + e.getMessage());
        }
    }

    // ==================== 状态机 ====================

    /**
     * 执行状态转换，拒绝无效转换。
     *
     * <p>有效转换：
     * <ul>
     *   <li>CREATED → RUNNING</li>
     *   <li>RUNNING → COMPLETED / FAILED / CANCELLED / WAITING</li>
     *   <li>WAITING → RUNNING</li>
     * </ul>
     *
     * @param instance   当前实例
     * @param targetState 目标状态
     * @return 转换后的新实例，无效转换时返回原实例不变
     */
    private WorkflowInstance transition(WorkflowInstance instance, WorkflowState targetState) {
        WorkflowState currentState = instance.state();

        if (!isValidTransition(currentState, targetState)) {
            log.warn("无效状态转换被拒绝: instanceId={}, from={}, to={}",
                    instance.id(), currentState, targetState);
            return instance;
        }

        Instant now = Instant.now();
        var builder = instance.toBuilder()
                .state(targetState)
                .updatedAt(now);

        // 设置 startedAt（CREATED → RUNNING）
        if (currentState == WorkflowState.CREATED && targetState == WorkflowState.RUNNING) {
            builder.startedAt(now);
        }

        // 设置 completedAt（终态）
        if (targetState == WorkflowState.COMPLETED
                || targetState == WorkflowState.FAILED
                || targetState == WorkflowState.CANCELLED) {
            builder.completedAt(now);
        }

        WorkflowInstance newInstance = builder.build();
        repository.updateInstance(newInstance);

        log.info("工作流状态转换: instanceId={}, {} → {}", instance.id(), currentState, targetState);
        return newInstance;
    }

    /**
     * 检查状态转换是否有效。
     */
    private boolean isValidTransition(WorkflowState from, WorkflowState to) {
        return switch (from) {
            case CREATED -> to == WorkflowState.RUNNING;
            case RUNNING -> to == WorkflowState.COMPLETED
                    || to == WorkflowState.FAILED
                    || to == WorkflowState.CANCELLED
                    || to == WorkflowState.WAITING;
            case WAITING -> to == WorkflowState.RUNNING;
            case PAUSED -> to == WorkflowState.RUNNING;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    /**
     * 将工作流标记为 FAILED。
     */
    private WorkflowInstance failWorkflow(WorkflowInstance instance, String reason) {
        WorkflowInstance failed = instance.toBuilder()
                .state(WorkflowState.FAILED)
                .failureReason(reason)
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.updateInstance(failed);
        log.error("工作流执行失败: instanceId={}, reason={}", instance.id(), reason);
        return failed;
    }

    // ==================== 崩溃恢复 ====================

    /**
     * 恢复单个中断实例。
     */
    private void recoverInstance(WorkflowInstance instance) {
        try {
            log.info("崩溃恢复实例: instanceId={}, state={}, stepIndex={}",
                    instance.id(), instance.state(), instance.currentStepIndex());

            // 验证上下文数据完整性
            if (instance.context() == null) {
                markRecoveryFailed(instance);
                return;
            }

            // 尝试序列化/反序列化验证上下文
            try {
                String json = instance.context().toJson();
                WorkflowContext.fromJson(json);
            } catch (Exception e) {
                log.error("崩溃恢复失败：上下文数据损坏: instanceId={}", instance.id(), e);
                markRecoveryFailed(instance);
                return;
            }

            // 查找工作流定义
            var defOpt = registry.find(instance.workflowId());
            if (defOpt.isEmpty()) {
                log.error("崩溃恢复失败：工作流定义未找到: instanceId={}, workflowId={}",
                        instance.id(), instance.workflowId());
                markRecoveryFailed(instance);
                return;
            }

            WorkflowDefinition definition = defOpt.get();

            if (instance.state() == WorkflowState.RUNNING) {
                // RUNNING 实例：从 currentStepIndex 恢复执行
                WorkflowInstance result = executeSteps(instance, definition.steps(),
                        instance.currentStepIndex(), 0);
                log.info("崩溃恢复完成: instanceId={}, finalState={}", instance.id(), result.state());
            } else if (instance.state() == WorkflowState.WAITING) {
                // WAITING 实例：恢复为 RUNNING 并从下一步继续
                WorkflowInstance running = transition(instance, WorkflowState.RUNNING);
                if (running != instance) {
                    WorkflowInstance result = executeSteps(running, definition.steps(),
                            running.currentStepIndex() + 1, 0);
                    log.info("崩溃恢复完成（WAITING→RUNNING）: instanceId={}, finalState={}",
                            instance.id(), result.state());
                }
            }
        } catch (Exception e) {
            log.error("崩溃恢复实例异常: instanceId={}", instance.id(), e);
            markRecoveryFailed(instance);
        }
    }

    /**
     * 标记实例崩溃恢复失败。
     */
    private void markRecoveryFailed(WorkflowInstance instance) {
        WorkflowInstance failed = instance.toBuilder()
                .state(WorkflowState.FAILED)
                .failureReason("崩溃恢复失败：上下文数据损坏")
                .completedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repository.updateInstance(failed);
    }

    // ==================== 工具方法 ====================

    /**
     * 从 WorkflowStep 类名提取步骤类型字符串。
     *
     * <p>例如：SkillStep → "skill"，ToolStep → "tool"，ConditionStep → "condition"。
     */
    private String extractStepType(WorkflowStep step) {
        String simpleName = step.getClass().getSimpleName();
        // 去掉 "Step" 后缀并转小写
        if (simpleName.endsWith("Step")) {
            return simpleName.substring(0, simpleName.length() - 4).toLowerCase();
        }
        return simpleName.toLowerCase();
    }

    /**
     * 将 Map 序列化为 JSON 字符串（用于 StepLog）。
     */
    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Map 序列化为 JSON 失败", e);
            return null;
        }
    }

    /**
     * 插入步骤执行日志。
     */
    private void insertStepLog(String instanceId, String stepId, String stepType,
                                StepState state, int attempt,
                                String inputJson, String outputJson, String errorMessage,
                                Instant startedAt) {
        Instant now = Instant.now();
        Long durationMs = (startedAt != null)
                ? java.time.Duration.between(startedAt, now).toMillis()
                : null;

        StepLog stepLog = new StepLog(
                UUID.randomUUID().toString(),
                instanceId,
                stepId,
                stepType,
                state,
                attempt,
                inputJson,
                outputJson,
                errorMessage,
                startedAt,
                now,
                durationMs,
                now
        );

        try {
            repository.insertStepLog(stepLog);
        } catch (Exception e) {
            log.warn("步骤日志插入失败: instanceId={}, stepId={}", instanceId, stepId, e);
        }
    }
}
