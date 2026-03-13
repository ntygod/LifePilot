package com.lifepilot.workflow.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.expression.ExpressionEngine.DryRunResolveResult;
import com.lifepilot.workflow.expression.ExpressionEvaluationException;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;

/**
 * 工作流试运行引擎 — 模拟执行工作流，不持久化状态，不触发实际步骤。
 *
 * <p>复用 {@link DagScheduler} 进行拓扑排序，遍历步骤解析表达式参数，
 * 对条件步骤求值分支结果，对循环步骤预估迭代次数，收集未解析变量警告。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class DryRunEngine {

    private static final Logger log = LoggerFactory.getLogger(DryRunEngine.class);

    private final DagScheduler dagScheduler;
    private final ExpressionEngine expressionEngine;

    public DryRunEngine(DagScheduler dagScheduler, ExpressionEngine expressionEngine) {
        this.dagScheduler = dagScheduler;
        this.expressionEngine = expressionEngine;
    }

    /**
     * 试运行工作流：模拟执行，不持久化状态。
     *
     * @param definition 工作流定义
     * @param inputs     输入参数（可空）
     * @return 试运行结果
     */
    public DryRunResult dryRun(WorkflowDefinition definition, @Nullable Map<String, Object> inputs) {
        log.info("试运行开始: workflowId={}", definition.id());

        // 1. 构建 DAG 执行计划
        ExecutionPlan plan;
        boolean dagValid;
        String dagError = null;
        try {
            plan = dagScheduler.buildExecutionPlan(definition.steps());
            dagValid = true;
        } catch (IllegalArgumentException e) {
            log.warn("试运行 DAG 校验失败: workflowId={}, error={}", definition.id(), e.getMessage());
            return new DryRunResult(definition.id(), List.of(), false, e.getMessage(), List.of());
        }

        // 2. 创建临时 WorkflowContext，注入 inputs 和 variables
        WorkflowContext context = new WorkflowContext();
        if (inputs != null && !inputs.isEmpty()) {
            context.set("inputs", inputs);
        }
        if (!definition.variables().isEmpty()) {
            Map<String, Object> resolvedVars = new java.util.LinkedHashMap<>();
            for (var entry : definition.variables().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String strVal && strVal.contains("${")) {
                    DryRunResolveResult r = expressionEngine.resolveDryRun(strVal, context);
                    resolvedVars.put(entry.getKey(), r.resolved());
                } else {
                    resolvedVars.put(entry.getKey(), value);
                }
            }
            context.set("vars", resolvedVars);
        }

        // 3. 计算拓扑层级
        Map<String, Integer> levelMap = computeLevels(plan);

        // 4. 遍历步骤，构建 DryRunStepTrace
        List<DryRunStepTrace> traces = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> allUnresolved = new HashSet<>();

        for (String stepId : plan.topologicalOrder()) {
            WorkflowStep step = plan.stepMap().get(stepId);
            String stepType = extractStepType(step);
            int level = levelMap.getOrDefault(stepId, 0);

            // 解析步骤参数
            Map<String, Object> resolvedParams = resolveStepParams(step, context, allUnresolved);

            // 条件步骤：求值分支结果
            String branchResult = null;
            if (step instanceof WorkflowStep.ConditionStep cs) {
                branchResult = evaluateConditionDryRun(cs.condition(), context, allUnresolved);
            }

            // 循环步骤：预估迭代次数
            Integer estimatedIterations = null;
            if (step instanceof WorkflowStep.LoopStep ls) {
                estimatedIterations = estimateLoopIterations(ls.items(), context, allUnresolved);
            }

            traces.add(new DryRunStepTrace(stepId, stepType, resolvedParams, branchResult, estimatedIterations, level));
        }

        // 5. 收集警告
        for (String path : allUnresolved) {
            warnings.add("变量未解析: " + path);
        }

        log.info("试运行完成: workflowId={}, steps={}, warnings={}", definition.id(), traces.size(), warnings.size());
        return new DryRunResult(definition.id(), traces, dagValid, dagError, warnings);
    }

    /**
     * 计算每个步骤的 DAG 拓扑层级（入度为 0 的步骤为第 0 层）。
     */
    private Map<String, Integer> computeLevels(ExecutionPlan plan) {
        Map<String, Integer> levels = new HashMap<>();
        for (String stepId : plan.topologicalOrder()) {
            Set<String> deps = plan.dependencyMap().getOrDefault(stepId, Set.of());
            int maxDepLevel = -1;
            for (String dep : deps) {
                maxDepLevel = Math.max(maxDepLevel, levels.getOrDefault(dep, 0));
            }
            levels.put(stepId, maxDepLevel + 1);
        }
        return levels;
    }

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
     * 解析步骤参数（dry-run 模式，缺失变量用占位符）。
     */
    private Map<String, Object> resolveStepParams(WorkflowStep step, WorkflowContext context,
                                                   Set<String> allUnresolved) {
        Map<String, String> rawParams = switch (step) {
            case WorkflowStep.SkillStep ss -> ss.params();
            case WorkflowStep.ToolStep ts -> ts.params();
            case WorkflowStep.SubWorkflowStep sw -> sw.params();
            default -> null;
        };

        Map<String, Object> resolved = new HashMap<>();

        // 解析 params Map
        if (rawParams != null && !rawParams.isEmpty()) {
            for (var entry : rawParams.entrySet()) {
                DryRunResolveResult r = expressionEngine.resolveDryRun(entry.getValue(), context);
                resolved.put(entry.getKey(), r.resolved());
                allUnresolved.addAll(r.unresolvedPaths());
            }
        }

        // 解析 LLM 步骤的 promptTemplate
        if (step instanceof WorkflowStep.LlmStep ls) {
            DryRunResolveResult r = expressionEngine.resolveDryRun(ls.promptTemplate(), context);
            resolved.put("promptTemplate", r.resolved());
            allUnresolved.addAll(r.unresolvedPaths());
        }

        // 解析 Notify 步骤的 content 和 targetUserId
        if (step instanceof WorkflowStep.NotifyStep ns) {
            DryRunResolveResult contentResult = expressionEngine.resolveDryRun(ns.content(), context);
            resolved.put("content", contentResult.resolved());
            allUnresolved.addAll(contentResult.unresolvedPaths());

            DryRunResolveResult targetResult = expressionEngine.resolveDryRun(ns.targetUserId(), context);
            resolved.put("targetUserId", targetResult.resolved());
            allUnresolved.addAll(targetResult.unresolvedPaths());
        }

        return Map.copyOf(resolved);
    }

    /**
     * 试运行模式求值条件表达式，返回分支结果描述。
     */
    private String evaluateConditionDryRun(String condition, WorkflowContext context,
                                            Set<String> allUnresolved) {
        try {
            // 先用 dry-run 模式解析变量
            DryRunResolveResult r = expressionEngine.resolveDryRun(condition, context);
            allUnresolved.addAll(r.unresolvedPaths());

            if (!r.unresolvedPaths().isEmpty()) {
                // 存在未解析变量，无法求值
                return "未知（表达式无法求值）";
            }

            // 所有变量已解析，尝试求值
            boolean result = expressionEngine.evaluateCondition(condition, context);
            return String.valueOf(result);
        } catch (ExpressionEvaluationException e) {
            log.debug("试运行条件求值失败: condition={}, error={}", condition, e.getMessage());
            return "未知（表达式无法求值）";
        }
    }

    /**
     * 预估循环迭代次数。
     */
    @Nullable
    private Integer estimateLoopIterations(String items, WorkflowContext context,
                                            Set<String> allUnresolved) {
        try {
            DryRunResolveResult r = expressionEngine.resolveDryRun(items, context);
            allUnresolved.addAll(r.unresolvedPaths());

            if (!r.unresolvedPaths().isEmpty()) {
                return null;
            }

            // 尝试从 context 获取集合值
            var value = context.get(items.replace("${", "").replace("}", "").trim());
            if (value.isPresent() && value.get() instanceof Collection<?> col) {
                return col.size();
            }
            return null;
        } catch (Exception e) {
            log.debug("试运行循环迭代预估失败: items={}, error={}", items, e.getMessage());
            return null;
        }
    }
}
