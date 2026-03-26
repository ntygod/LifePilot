package com.lifepilot.workflow.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.lifepilot.llm.config.ProviderCapability;
import org.springframework.lang.Nullable;

/**
 * 工作流步骤类型 sealed interface。
 *
 * <p>定义工作流中可执行的十一种步骤类型：
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
 *   <li>{@link ApprovalStep} — 人工审批步骤，暂停工作流等待审批决策</li>
 *   <li>{@link NotifyStep} — 通知步骤，通过 NotificationService 发送通知</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WorkflowStep.SkillStep.class, name = "skill"),
        @JsonSubTypes.Type(value = WorkflowStep.ToolStep.class, name = "tool"),
        @JsonSubTypes.Type(value = WorkflowStep.LlmStep.class, name = "llm"),
        @JsonSubTypes.Type(value = WorkflowStep.ConditionStep.class, name = "condition"),
        @JsonSubTypes.Type(value = WorkflowStep.LoopStep.class, name = "loop"),
        @JsonSubTypes.Type(value = WorkflowStep.ParallelStep.class, name = "parallel"),
        @JsonSubTypes.Type(value = WorkflowStep.SubWorkflowStep.class, name = "sub-workflow"),
        @JsonSubTypes.Type(value = WorkflowStep.NoopStep.class, name = "noop"),
        @JsonSubTypes.Type(value = WorkflowStep.WaitStep.class, name = "wait"),
        @JsonSubTypes.Type(value = WorkflowStep.ApprovalStep.class, name = "approval"),
        @JsonSubTypes.Type(value = WorkflowStep.NotifyStep.class, name = "notify")
})
public sealed interface WorkflowStep permits
        WorkflowStep.SkillStep,
        WorkflowStep.ToolStep,
        WorkflowStep.LlmStep,
        WorkflowStep.ConditionStep,
        WorkflowStep.LoopStep,
        WorkflowStep.ParallelStep,
        WorkflowStep.SubWorkflowStep,
        WorkflowStep.NoopStep,
        WorkflowStep.WaitStep,
        WorkflowStep.ApprovalStep,
        WorkflowStep.NotifyStep {

    /** 步骤唯一标识。 */
    String id();

    /** 步骤名称。 */
    String name();

    /** 错误处理策略，为 null 时默认使用 {@link ErrorStrategy.Fail}。 */
    @Nullable ErrorStrategy errorStrategy();

    /** DAG 依赖声明，列出当前步骤依赖的前置步骤 ID。 */
    List<String> dependsOn();

    /** 步骤级超时时间（秒），为 null 时使用全局默认值。 */
    @Nullable Integer timeoutSeconds();

    /**
     * Skill 步骤，通过 SkillActivator 激活已注册的 Skill。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param skillId       目标 Skill ID
     * @param params        传递给 Skill 的参数（支持 ${} 表达式）
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record SkillStep(String id, String name, String skillId,
                     Map<String, String> params,
                     List<String> dependsOn,
                     @Nullable ErrorStrategy errorStrategy,
                     @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * Tool 步骤，通过 DynamicToolRegistry 查找并执行工具。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param toolId        目标 Tool ID
     * @param params        传递给 Tool 的参数（支持 ${} 表达式）
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record ToolStep(String id, String name, String toolId,
                    Map<String, String> params,
                    List<String> dependsOn,
                    @Nullable ErrorStrategy errorStrategy,
                    @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * LLM/多模态节点引用的媒体输入。
     *
     * @param source   媒体来源，可为文件路径、data URL 或 Base64 字符串
     * @param mimeType 可选 MIME 类型，Base64 场景建议显式填写
     * @param fileName 可选文件名
     */
    record MediaRef(String source,
                    @Nullable String mimeType,
                    @Nullable String fileName) {}

    /**
     * LLM 步骤，调用生成路由生成内容。
     *
     * @param id             步骤唯一标识
     * @param name           步骤名称
     * @param scene          LLM 场景标识
     * @param promptTemplate 提示词模板（支持 ${} 表达式）
     * @param outputSchema   可选的输出 JSON Schema，用于结构化输出
     * @param dependsOn      DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy  错误处理策略
     */
    record LlmStep(String id, String name, String scene, ProviderCapability capability,
                   String promptTemplate,
                   @Nullable String outputSchema,
                   @Nullable String modelName,
                   @Nullable String preferredProviderId,
                   List<MediaRef> media,
                   List<String> dependsOn,
                   @Nullable ErrorStrategy errorStrategy,
                   @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 条件分支步骤，根据表达式求值结果选择执行 then 或 else 分支。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param condition     条件表达式
     * @param thenSteps     条件为 true 时执行的步骤列表
     * @param elseSteps     条件为 false 时执行的步骤列表
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record ConditionStep(String id, String name, String condition,
                         List<WorkflowStep> thenSteps,
                         List<WorkflowStep> elseSteps,
                         List<String> dependsOn,
                         @Nullable ErrorStrategy errorStrategy,
                         @Nullable Integer timeoutSeconds) implements WorkflowStep {}

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
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record LoopStep(String id, String name, String items, String loopVar,
                    List<WorkflowStep> body,
                    List<String> dependsOn,
                    @Nullable ErrorStrategy errorStrategy,
                    @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 并行步骤，使用 Virtual Thread 并发执行多个分支。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param branches      并行分支列表，每个分支是一组步骤
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record ParallelStep(String id, String name,
                        List<List<WorkflowStep>> branches,
                        List<String> dependsOn,
                        @Nullable ErrorStrategy errorStrategy,
                        @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 子工作流步骤，查找并执行另一个已注册的工作流。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param workflowId    目标工作流 ID
     * @param params        传递给子工作流的输入参数（支持 ${} 表达式）
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record SubWorkflowStep(String id, String name, String workflowId,
                           Map<String, String> params,
                           List<String> dependsOn,
                           @Nullable ErrorStrategy errorStrategy,
                           @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 空操作步骤，不执行任何操作，直接返回空结果。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record NoopStep(String id, String name,
                    List<String> dependsOn,
                    @Nullable ErrorStrategy errorStrategy,
                    @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 等待步骤，暂停工作流执行指定时长。
     *
     * <p>引擎将工作流状态转换为 WAITING，持久化当前位置，等待时长到达后恢复执行。
     *
     * @param id              步骤唯一标识
     * @param name            步骤名称
     * @param durationSeconds 等待时长（秒）
     * @param dependsOn       DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy   错误处理策略
     */
    record WaitStep(String id, String name, long durationSeconds,
                    List<String> dependsOn,
                    @Nullable ErrorStrategy errorStrategy,
                    @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 人工审批步骤，暂停工作流等待审批决策。
     *
     * <p>引擎遇到 ApprovalStep 时将工作流状态从 RUNNING 转换为 PAUSED，
     * 等待外部通过 {@code WorkflowEngine.approve()} 提交审批决策。
     *
     * @param id                    步骤唯一标识
     * @param name                  步骤名称
     * @param message               审批消息（展示给审批人）
     * @param approvers             审批人列表
     * @param approvalTimeoutSeconds 审批超时时间（秒）
     * @param autoApproveOnTimeout  超时后是否自动批准
     * @param dependsOn             DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy         错误处理策略
     */
    record ApprovalStep(String id, String name, String message,
                        List<String> approvers,
                        int approvalTimeoutSeconds,
                        boolean autoApproveOnTimeout,
                        List<String> dependsOn,
                        @Nullable ErrorStrategy errorStrategy,
                        @Nullable Integer timeoutSeconds) implements WorkflowStep {}

    /**
     * 通知步骤，通过 NotificationService 发送通知。
     *
     * @param id            步骤唯一标识
     * @param name          步骤名称
     * @param targetUserId  目标用户 ID（支持 ${} 表达式）
     * @param content       通知内容模板（支持 ${} 表达式）
     * @param contentType   内容类型：TEXT / MARKDOWN / CARD
     * @param dependsOn     DAG 依赖的前置步骤 ID 列表
     * @param errorStrategy 错误处理策略
     */
    record NotifyStep(String id, String name, String targetUserId,
                      String content, String contentType,
                      List<String> dependsOn,
                      @Nullable ErrorStrategy errorStrategy,
                      @Nullable Integer timeoutSeconds) implements WorkflowStep {}
}
