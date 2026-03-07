package com.lifepilot.workflow.model;

import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * 工作流步骤类型 sealed interface。
 *
 * <p>定义工作流中可执行的九种步骤类型：
 * <ul>
 *   <li>{@link SkillStep} — 调用已注册的 Skill 执行</li>
 *   <li>{@link ToolStep} — 调用已注册的 Tool 执行</li>
 *   <li>{@link LlmStep} — 调用 LLM 生成内容</li>
 *   <li>{@link ConditionStep} — 条件分支，根据表达式求值选择 then/else 分支</li>
 *   <li>{@link LoopStep} — 循环遍历集合，对每个元素执行 body 步骤</li>
 *   <li>{@link ParallelStep} — 并行执行多个分支（Virtual Thread）</li>
 *   <li>{@link SubWorkflowStep} — 调用子工作流</li>
 *   <li>{@link NoopStep} — 空操作，直接跳过</li>
 *   <li>{@link WaitStep} — 等待指定时长后继续</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface WorkflowStep permits
        WorkflowStep.SkillStep,
        WorkflowStep.ToolStep,
        WorkflowStep.LlmStep,
        WorkflowStep.ConditionStep,
        WorkflowStep.LoopStep,
        WorkflowStep.ParallelStep,
        WorkflowStep.SubWorkflowStep,
        WorkflowStep.NoopStep,
        WorkflowStep.WaitStep {

    /** 步骤唯一标识。 */
    String id();

    /** 步骤名称。 */
    String name();

    /** 错误处理策略，为 null 时默认使用 {@link ErrorStrategy.Fail}。 */
    @Nullable ErrorStrategy errorStrategy();

    /**
     * Skill 步骤，通过 SkillActivator 激活已注册的 Skill。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param skillId       目标 Skill ID
     * @param params        传递给 Skill 的参数（支持 ${} 表达式）
     * @param errorStrategy 错误处理策略
     */
    record SkillStep(String id, String name, String skillId,
                     Map<String, String> params,
                     @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * Tool 步骤，通过 DynamicToolRegistry 查找并执行工具。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param toolId        目标 Tool ID
     * @param params        传递给 Tool 的参数（支持 ${} 表达式）
     * @param errorStrategy 错误处理策略
     */
    record ToolStep(String id, String name, String toolId,
                    Map<String, String> params,
                    @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * LLM 步骤，调用 LlmRouter 生成内容。
     *
     * @param id             步骤唯一标识
     * @param name           步骤名称
     * @param scene          LLM 场景标识
     * @param promptTemplate 提示词模板（支持 ${} 表达式）
     * @param outputSchema   可选的输出 JSON Schema，用于结构化输出
     * @param errorStrategy  错误处理策略
     */
    record LlmStep(String id, String name, String scene, String promptTemplate,
                   @Nullable String outputSchema,
                   @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 条件分支步骤，根据表达式求值结果选择执行 then 或 else 分支。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param condition     条件表达式
     * @param thenSteps     条件为 true 时执行的步骤列表
     * @param elseSteps     条件为 false 时执行的步骤列表
     * @param errorStrategy 错误处理策略
     */
    record ConditionStep(String id, String name, String condition,
                         List<WorkflowStep> thenSteps,
                         List<WorkflowStep> elseSteps,
                         @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 循环步骤，遍历集合对每个元素执行 body 步骤。
     *
     * <p>每次迭代时，{@code loopVar} 绑定为当前元素，{@code loopVar_index} 绑定为当前索引。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param items         集合表达式（解析为 List）
     * @param loopVar       循环变量名
     * @param body          每次迭代执行的步骤列表
     * @param errorStrategy 错误处理策略
     */
    record LoopStep(String id, String name, String items, String loopVar,
                    List<WorkflowStep> body,
                    @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 并行步骤，使用 Virtual Thread 并发执行多个分支。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param branches      并行分支列表，每个分支是一组步骤
     * @param errorStrategy 错误处理策略
     */
    record ParallelStep(String id, String name,
                        List<List<WorkflowStep>> branches,
                        @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 子工作流步骤，查找并执行另一个已注册的工作流。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param workflowId    目标工作流 ID
     * @param params        传递给子工作流的输入参数（支持 ${} 表达式）
     * @param errorStrategy 错误处理策略
     */
    record SubWorkflowStep(String id, String name, String workflowId,
                           Map<String, String> params,
                           @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 空操作步骤，不执行任何操作，直接返回空结果。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param errorStrategy 错误处理策略
     */
    record NoopStep(String id, String name,
                    @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}

    /**
     * 等待步骤，暂停工作流执行指定时长。
     *
     * <p>引擎将工作流状态转换为 WAITING，持久化当前位置，等待时长到达后恢复执行。
     *
     * @param id              步骤唯一标识
     * @param name            步骤名称
     * @param durationSeconds 等待时长（秒）
     * @param errorStrategy   错误处理策略
     */
    record WaitStep(String id, String name, long durationSeconds,
                    @Nullable ErrorStrategy errorStrategy) implements WorkflowStep {}
}
