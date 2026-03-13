package com.lifepilot.workflow.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.ValidationResponse;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowEventType;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
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
    private final ExpressionEngine expressionEngine;
    private final WorkflowConfigProperties config;
    private final DryRunEngine dryRunEngine;
    private final WorkflowYamlParser parser;
    private final DagScheduler dagScheduler;

    public WorkflowCommandService(WorkflowRegistry registry,
                                  WorkflowRepository repository,
                                  WorkflowRunner runner,
                                  WorkflowEventRecorder eventRecorder,
                                  ExpressionEngine expressionEngine,
                                  WorkflowConfigProperties config,
                                  DryRunEngine dryRunEngine,
                                  WorkflowYamlParser parser,
                                  DagScheduler dagScheduler) {
        this.registry = registry;
        this.repository = repository;
        this.runner = runner;
        this.eventRecorder = eventRecorder;
        this.expressionEngine = expressionEngine;
        this.config = config;
        this.dryRunEngine = dryRunEngine;
        this.parser = parser;
        this.dagScheduler = dagScheduler;
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

        // 防御性输入校验（安全网）
        InputValidationResult validation = InputValidator.validate(definition.inputs(), inputs);
        if (!validation.valid()) {
            throw new IllegalArgumentException(
                    "缺少必填输入参数: " + String.join(", ", validation.missingParams()));
        }

        // 速率限制检查
        WorkflowConfigProperties.RateLimit rateLimit = config.getRateLimit();

        // 全局并发实例数检查
        java.util.List<WorkflowInstance> runningInstances = repository.findInstancesByState(
                WorkflowState.RUNNING, WorkflowState.WAITING, WorkflowState.PAUSED);
        if (runningInstances.size() >= rateLimit.getMaxConcurrentInstances()) {
            throw new IllegalStateException(
                    "全局并发实例数已达上限: current=" + runningInstances.size()
                    + ", max=" + rateLimit.getMaxConcurrentInstances());
        }

        // 单工作流并发实例数检查
        long workflowRunningCount = runningInstances.stream()
                .filter(i -> workflowId.equals(i.workflowId()))
                .count();
        if (workflowRunningCount >= rateLimit.getMaxInstancesPerWorkflow()) {
            throw new IllegalStateException(
                    "工作流并发实例数已达上限: workflowId=" + workflowId
                    + ", current=" + workflowRunningCount
                    + ", max=" + rateLimit.getMaxInstancesPerWorkflow());
        }

        // 创建实例（CREATED 状态）
        Instant now = Instant.now();
        WorkflowContext context = new WorkflowContext();
        Map<String, Object> mergedInputs = validation.mergedInputs();
        if (!mergedInputs.isEmpty()) {
            context.set("inputs", mergedInputs);
        }

        // 解析并注入工作流级变量到 vars 命名空间
        if (!definition.variables().isEmpty()) {
            Map<String, Object> resolvedVars = new LinkedHashMap<>();
            for (var entry : definition.variables().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String strVal && strVal.contains("${")) {
                    resolvedVars.put(entry.getKey(), expressionEngine.resolve(strVal, context));
                } else {
                    resolvedVars.put(entry.getKey(), value);
                }
            }
            context.set("vars", resolvedVars);
            log.debug("工作流变量注入完成: workflowId={}, vars={}", workflowId, resolvedVars.keySet());
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

    /**
     * 试运行工作流：模拟执行，不持久化状态。
     *
     * @param workflowId 工作流定义 ID
     * @param inputs     输入参数（可空）
     * @return 试运行结果
     */
    public DryRunResult dryRun(String workflowId, @Nullable Map<String, Object> inputs) {
        var definition = registry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("工作流定义未找到: workflowId=" + workflowId));
        return dryRunEngine.dryRun(definition, inputs);
    }

    /**
     * 校验 YAML 工作流定义。
     *
     * @param yamlContent YAML 内容
     * @return 校验响应
     */
    public ValidationResponse validateYaml(String yamlContent) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean dagValid = true;
        String dagError = null;

        // 1. YAML 语法解析
        Result<WorkflowDefinition, List<String>> parseResult = parser.parse(yamlContent);
        if (parseResult instanceof Result.Err<WorkflowDefinition, List<String>> err) {
            return new ValidationResponse(false, err.error(), List.of(), false, "解析失败，无法校验 DAG");
        }

        WorkflowDefinition definition = ((Result.Ok<WorkflowDefinition, List<String>>) parseResult).value();

        // 2. 语义校验
        if (definition.steps().isEmpty()) {
            warnings.add("工作流未定义任何步骤");
        }
        if (definition.name() == null || definition.name().isBlank()) {
            errors.add("工作流名称不能为空");
        }

        // 3. DAG 环检测
        try {
            dagScheduler.buildExecutionPlan(definition.steps());
        } catch (IllegalArgumentException e) {
            dagValid = false;
            dagError = e.getMessage();
            errors.add("DAG 存在环: " + e.getMessage());
        }

        boolean valid = errors.isEmpty();
        return new ValidationResponse(valid, errors, warnings, dagValid, dagError);
    }

}
