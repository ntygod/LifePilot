package com.lifepilot.workflow.engine;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流步骤分发器 — 使用 switch 表达式穷举匹配 10 种步骤类型。
 *
 * <p>根据 {@link WorkflowStep} 的具体类型分发到对应的执行逻辑：
 * <ul>
 *   <li>{@link SkillStep} → {@link SkillActivator#activate}</li>
 *   <li>{@link ToolStep} → {@link DynamicToolRegistry} + {@link ToolContract#execute}</li>
 *   <li>{@link LlmStep} → {@link LlmRouter#call}</li>
 *   <li>{@link ConditionStep} → 条件求值 + 递归执行分支</li>
 *   <li>{@link LoopStep} → 遍历集合 + 递归执行 body</li>
 *   <li>{@link ParallelStep} → Virtual Thread 并发执行分支</li>
 *   <li>{@link SubWorkflowStep} → 返回特殊标记，由 WorkflowEngine 处理</li>
 *   <li>{@link NoopStep} → 返回空 Map</li>
 *   <li>{@link WaitStep} → 返回特殊标记，由 WorkflowEngine 处理状态转换</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final SkillRegistry skillRegistry;
    private final SkillActivator skillActivator;
    private final DynamicToolRegistry toolRegistry;
    private final LlmRouter llmRouter;
    private final WorkflowConfigProperties config;

    /**
     * 构造步骤分发器。
     *
     * @param skillRegistry  Skill 注册中心
     * @param skillActivator Skill 激活器
     * @param toolRegistry   动态工具注册中心
     * @param llmRouter      LLM 路由器
     * @param config         工作流配置属性
     */
    public StepExecutor(SkillRegistry skillRegistry,
                        SkillActivator skillActivator,
                        DynamicToolRegistry toolRegistry,
                        LlmRouter llmRouter,
                        WorkflowConfigProperties config) {
        this.skillRegistry = skillRegistry;
        this.skillActivator = skillActivator;
        this.toolRegistry = toolRegistry;
        this.llmRouter = llmRouter;
        this.config = config;
    }

    /**
     * 执行单个步骤，返回步骤输出。
     *
     * <p>使用 switch 表达式穷举匹配 {@link WorkflowStep} 的 10 种子类型，
     * 确保编译期覆盖所有步骤类型。
     *
     * @param step             待执行的步骤
     * @param context          工作流变量上下文
     * @param expressionEngine 表达式引擎
     * @return 步骤输出 Map
     */
    public Map<String, Object> execute(WorkflowStep step,
                                       WorkflowContext context,
                                       ExpressionEngine expressionEngine) {
        log.info("步骤分发: stepId={}, stepType={}", step.id(), step.getClass().getSimpleName());

        Map<String, Object> result = switch (step) {
            case SkillStep s -> executeSkill(s, context, expressionEngine);
            case ToolStep s -> executeTool(s, context, expressionEngine);
            case LlmStep s -> executeLlm(s, context, expressionEngine);
            case ConditionStep s -> executeCondition(s, context, expressionEngine);
            case LoopStep s -> executeLoop(s, context, expressionEngine);
            case ParallelStep s -> executeParallel(s, context, expressionEngine);
            case SubWorkflowStep s -> executeSubWorkflow(s, context, expressionEngine);
            case NoopStep s -> executeNoop(s);
            case WaitStep s -> executeWait(s);
            case ApprovalStep s -> executeApproval(s);
        };

        log.info("步骤分发完成: stepId={}, outputKeys={}", step.id(), result.keySet());
        return result;
    }

    // ========== 各步骤类型执行逻辑 ==========

    /**
     * 执行 SkillStep — 通过 SkillActivator 激活 Skill，返回指令和建议工具。
     */
    private Map<String, Object> executeSkill(SkillStep step,
                                             WorkflowContext context,
                                             ExpressionEngine expressionEngine) {
        log.info("执行 SkillStep: stepId={}, skillId={}", step.id(), step.skillId());

        // 1. 激活 Skill
        SkillActivation activation;
        try {
            activation = skillActivator.activate(step.skillId());
        } catch (Exception e) {
            throw new WorkflowStepException(
                    step.id(), "Skill 激活失败: skillId=" + step.skillId() + ", error=" + e.getMessage(), e);
        }

        // 2. 返回激活结果
        Map<String, Object> output = new HashMap<>();
        output.put("success", true);
        output.put("skillId", activation.skillId());
        output.put("instructions", activation.instructions());
        output.put("suggestedTools", activation.suggestedTools());
        return Map.copyOf(output);
    }

    /**
     * 执行 ToolStep — 通过 DynamicToolRegistry 查找工具并执行。
     */
    private Map<String, Object> executeTool(ToolStep step,
                                            WorkflowContext context,
                                            ExpressionEngine expressionEngine) {
        log.info("执行 ToolStep: stepId={}, toolId={}", step.id(), step.toolId());

        // 1. 查找工具
        ToolContract tool = toolRegistry.resolve(step.toolId())
                .orElseThrow(() -> new WorkflowStepException(
                        step.id(), "工具未找到: toolId=" + step.toolId()));

        // 2. 解析参数表达式
        Map<String, Object> resolvedParams = expressionEngine.resolveMap(
                toObjectMap(step.params()), context);

        // 3. 构建 ToolInput 并执行
        var toolInput = new ToolInput(step.toolId(), resolvedParams, JsonSchema.empty(), null);
        ToolResult toolResult = tool.execute(toolInput);

        // 4. 转换为输出 Map
        Map<String, Object> output = new HashMap<>();
        output.put("ok", toolResult.ok());
        output.put("data", toolResult.data());
        if (toolResult.error() != null) {
            output.put("error", toolResult.error());
            log.warn("ToolStep 执行返回错误: stepId={}, toolId={}, error={}",
                    step.id(), step.toolId(), toolResult.error());
        } else {
            log.info("ToolStep 执行成功: stepId={}, toolId={}, ok={}", step.id(), step.toolId(), toolResult.ok());
        }
        return Map.copyOf(output);
    }

    /**
     * 执行 LlmStep — 通过 LlmRouter 调用 LLM 生成内容。
     */
    private Map<String, Object> executeLlm(LlmStep step,
                                           WorkflowContext context,
                                           ExpressionEngine expressionEngine) {
        log.info("执行 LlmStep: stepId={}, scene={}", step.id(), step.scene());

        // 1. 解析 prompt 模板中的表达式
        String resolvedPrompt = expressionEngine.resolve(step.promptTemplate(), context);

        // 2. 解析 outputSchema（如有）
        String resolvedSchema = step.outputSchema() != null
                ? expressionEngine.resolve(step.outputSchema(), context)
                : null;

        // 3. 调用 LLM
        var llmResponse = llmRouter.call(step.scene(), resolvedPrompt, resolvedSchema);

        log.info("LlmStep 调用完成: stepId={}, scene={}, provider={}, model={}, 输入tokens={}, 输出tokens={}, 耗时={}ms",
                step.id(), step.scene(), llmResponse.providerId(), llmResponse.modelName(),
                llmResponse.inputTokens(), llmResponse.outputTokens(), llmResponse.latencyMs());

        // 4. 转换为输出 Map
        return Map.of(
                "content", llmResponse.content(),
                "providerId", llmResponse.providerId(),
                "modelName", llmResponse.modelName(),
                "inputTokens", llmResponse.inputTokens(),
                "outputTokens", llmResponse.outputTokens(),
                "latencyMs", llmResponse.latencyMs()
        );
    }

    /**
     * 执行 ConditionStep — 求值条件表达式，递归执行 then/else 分支。
     */
    private Map<String, Object> executeCondition(ConditionStep step,
                                                 WorkflowContext context,
                                                 ExpressionEngine expressionEngine) {
        log.info("执行 ConditionStep: stepId={}, condition={}", step.id(), step.condition());

        boolean conditionResult = expressionEngine.evaluateCondition(step.condition(), context);
        log.info("ConditionStep 求值: stepId={}, condition={}, result={}, 分支={}",
                step.id(), step.condition(), conditionResult, conditionResult ? "then" : "else");

        List<WorkflowStep> branch = conditionResult ? step.thenSteps() : step.elseSteps();
        String branchName = conditionResult ? "then" : "else";

        // 递归执行分支中的每个步骤
        Map<String, Object> branchOutput = new HashMap<>();
        for (WorkflowStep branchStep : branch) {
            Map<String, Object> stepOutput = execute(branchStep, context, expressionEngine);
            // 将分支步骤输出存入上下文
            context.set("steps." + branchStep.id() + ".output", stepOutput);
            branchOutput.put(branchStep.id(), stepOutput);
        }

        return Map.of(
                "conditionResult", conditionResult,
                "branch", branchName,
                "branchOutput", Map.copyOf(branchOutput)
        );
    }

    /**
     * 执行 LoopStep — 遍历集合，绑定 loopVar 和 loopVar_index，递归执行 body。
     *
     * <p>迭代次数不超过 {@link WorkflowConfigProperties#getMaxLoopIterations()} 配置。
     */
    private Map<String, Object> executeLoop(LoopStep step,
                                            WorkflowContext context,
                                            ExpressionEngine expressionEngine) {
        log.info("执行 LoopStep: stepId={}, loopVar={}", step.id(), step.loopVar());

        // 1. 解析 items 表达式为集合（从上下文中获取路径对应的值）
        Object itemsValue = context.get(extractPath(step.items())).orElse(null);

        if (!(itemsValue instanceof List<?> items)) {
            throw new WorkflowStepException(
                    step.id(), "LoopStep items 表达式未解析为 List: items=" + step.items());
        }

        // 2. 检查迭代次数上限
        int maxIterations = config.getMaxLoopIterations();
        if (items.size() > maxIterations) {
            throw new WorkflowStepException(
                    step.id(), "LoopStep 迭代次数超过上限: size=" + items.size()
                    + ", maxLoopIterations=" + maxIterations);
        }

        // 3. 遍历集合，逐个执行 body
        List<Map<String, Object>> iterationOutputs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            // 绑定循环变量
            context.set(step.loopVar(), items.get(i));
            context.set(step.loopVar() + "_index", i);

            log.debug("LoopStep 迭代: stepId={}, index={}, loopVar={}", step.id(), i, step.loopVar());

            // 执行 body 中的每个步骤
            Map<String, Object> bodyOutput = new HashMap<>();
            for (WorkflowStep bodyStep : step.body()) {
                Map<String, Object> stepOutput = execute(bodyStep, context, expressionEngine);
                context.set("steps." + bodyStep.id() + ".output", stepOutput);
                bodyOutput.put(bodyStep.id(), stepOutput);
            }
            iterationOutputs.add(Map.copyOf(bodyOutput));
        }

        log.info("LoopStep 完成: stepId={}, 迭代次数={}", step.id(), iterationOutputs.size());

        return Map.of(
                "iterations", iterationOutputs.size(),
                "results", List.copyOf(iterationOutputs)
        );
    }

    /**
     * 执行 ParallelStep — 使用 Virtual Thread 并发执行所有分支，等待全部完成。
     *
     * <p>分支数不超过 {@link WorkflowConfigProperties#getMaxParallelBranches()} 配置。
     */
    private Map<String, Object> executeParallel(ParallelStep step,
                                                WorkflowContext context,
                                                ExpressionEngine expressionEngine) {
        log.info("执行 ParallelStep: stepId={}, branchCount={}", step.id(), step.branches().size());

        // 1. 检查分支数上限
        int maxBranches = config.getMaxParallelBranches();
        if (step.branches().size() > maxBranches) {
            throw new WorkflowStepException(
                    step.id(), "ParallelStep 分支数超过上限: size=" + step.branches().size()
                    + ", maxParallelBranches=" + maxBranches);
        }

        // 2. 使用 Virtual Thread 并发执行各分支
        Map<Integer, Map<String, Object>> branchResults = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < step.branches().size(); i++) {
            final int branchIndex = i;
            List<WorkflowStep> branch = step.branches().get(i);

            Thread vThread = Thread.ofVirtual()
                    .name("parallel-" + step.id() + "-branch-" + branchIndex)
                    .start(() -> {
                        Map<String, Object> branchOutput = new HashMap<>();
                        for (WorkflowStep branchStep : branch) {
                            Map<String, Object> stepOutput = execute(branchStep, context, expressionEngine);
                            context.set("steps." + branchStep.id() + ".output", stepOutput);
                            branchOutput.put(branchStep.id(), stepOutput);
                        }
                        branchResults.put(branchIndex, Map.copyOf(branchOutput));
                    });
            threads.add(vThread);
        }

        // 3. 等待所有分支完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowStepException(
                        step.id(), "ParallelStep 等待分支完成时被中断");
            }
        }

        log.info("ParallelStep 完成: stepId={}, 分支数={}", step.id(), step.branches().size());

        return Map.of(
                "branchCount", step.branches().size(),
                "branches", Map.copyOf(branchResults)
        );
    }

    /**
     * 执行 SubWorkflowStep — 返回特殊标记 Map，由 WorkflowEngine 处理子工作流执行。
     *
     * <p>为避免 StepExecutor 与 WorkflowEngine 的循环依赖，
     * SubWorkflowStep 不在此处直接执行，而是返回标记让 WorkflowEngine 处理。
     */
    private Map<String, Object> executeSubWorkflow(SubWorkflowStep step,
                                                   WorkflowContext context,
                                                   ExpressionEngine expressionEngine) {
        log.info("执行 SubWorkflowStep: stepId={}, workflowId={}", step.id(), step.workflowId());

        // 解析参数表达式
        Map<String, Object> resolvedParams = expressionEngine.resolveMap(
                toObjectMap(step.params()), context);

        // 返回特殊标记，由 WorkflowEngine 处理
        return Map.of(
                "__type", "sub-workflow",
                "workflowId", step.workflowId(),
                "params", resolvedParams
        );
    }

    /**
     * 执行 NoopStep — 不执行任何操作，直接返回空 Map。
     */
    private Map<String, Object> executeNoop(NoopStep step) {
        log.debug("执行 NoopStep: stepId={}", step.id());
        return Map.of();
    }

    /**
     * 执行 WaitStep — 返回特殊标记 Map，由 WorkflowEngine 处理状态转换为 WAITING。
     */
    private Map<String, Object> executeWait(WaitStep step) {
        log.info("执行 WaitStep: stepId={}, durationSeconds={}", step.id(), step.durationSeconds());

        // 返回特殊标记，由 WorkflowEngine 处理
        return Map.of(
                "__type", "wait",
                "durationSeconds", step.durationSeconds()
        );
    }

    /**
     * 执行 ApprovalStep — 返回特殊标记 Map，由 WorkflowEngine 处理状态转换为 PAUSED。
     */
    private Map<String, Object> executeApproval(ApprovalStep step) {
        log.info("执行 ApprovalStep: stepId={}, message={}, approvers={}",
                step.id(), step.message(), step.approvers());

        return Map.of(
                "__type", "approval",
                "message", step.message(),
                "approvers", step.approvers(),
                "timeoutSeconds", step.timeoutSeconds(),
                "autoApproveOnTimeout", step.autoApproveOnTimeout()
        );
    }

    // ========== 工具方法 ==========

    /**
     * 将 Map&lt;String, String&gt; 转换为 Map&lt;String, Object&gt;。
     */
    private Map<String, Object> toObjectMap(Map<String, String> stringMap) {
        if (stringMap == null || stringMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>(stringMap.size());
        stringMap.forEach(result::put);
        return result;
    }

    /**
     * 从 ${path} 表达式中提取路径部分。
     */
    private String extractPath(String expression) {
        if (expression == null) {
            return "";
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /**
     * 步骤执行异常 — 包装步骤 ID 和错误信息。
     */
    public static class WorkflowStepException extends RuntimeException {
        private final String stepId;

        public WorkflowStepException(String stepId, String message) {
            super(message);
            this.stepId = stepId;
        }

        public WorkflowStepException(String stepId, String message, Throwable cause) {
            super(message, cause);
            this.stepId = stepId;
        }

        public String stepId() {
            return stepId;
        }
    }
}
