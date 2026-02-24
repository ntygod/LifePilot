package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.StepRecord;

import java.util.List;

/**
 * 基础版上下文组装器。
 *
 * <p>根据 AgentPhase 分配 Token 预算到六个槽位，
 * 组装 System Prompt 和 User Prompt。
 * 基础版不含记忆检索，记忆检索槽位返回空列表。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class ContextAssembler {

    private final AgentConfigProperties config;

    public ContextAssembler(AgentConfigProperties config) {
        this.config = config;
    }

    /**
     * 根据当前状态组装上下文。
     *
     * @param state 当前 Agent 状态
     * @return 组装完成的上下文快照
     */
    public AssembledContext assemble(AgentState state) {
        int totalTokens = config.getContext().getMaxContextTokens();
        var tokenBudget = TokenBudget.allocate(state.phase(), totalTokens);

        String systemPrompt = buildSystemPrompt(state.phase());
        String userPrompt = buildUserPrompt(state);

        // 基础版记忆检索返回空列表
        return new AssembledContext(systemPrompt, userPrompt, List.of(), tokenBudget,
                0, 0.0f, 0, false);
    }

    /**
     * 构建阶段专用 System Prompt。
     *
     * @param phase 当前阶段
     * @return System Prompt 文本
     */
    String buildSystemPrompt(AgentPhase phase) {
        String roleDefinition = "你是 LifePilot，一个智能个人助手。";
        String phaseInstruction = switch (phase) {
            case UNDERSTANDING -> "当前阶段：意图理解。分析用户输入，提取关键实体和意图，评估任务复杂度。"
                    + "如果需要澄清，设置 needsClarification=true 并提供澄清问题。"
                    + "如果可以继续，设置 canProceed=true。";
            case PLANNING -> "当前阶段：任务规划。根据理解的意图制定执行计划，"
                    + "列出需要调用的工具及其参数，评估所需 Token 预算。";
            case EXECUTING -> "当前阶段：工具执行。按计划执行工具调用，"
                    + "记录每步结果，判断是否还有后续步骤。";
            case REFLECTING -> "当前阶段：反思评估。评估执行结果是否满足用户意图，"
                    + "决定是否需要重新规划或直接生成响应。";
            case RESPONDING -> "当前阶段：生成响应。基于执行结果生成最终回复，"
                    + "提供清晰、有用的信息。";
            case TERMINATED -> "";
        };
        String constraint = "请严格按照 JSON 格式输出结构化结果。";

        return roleDefinition + "\n" + phaseInstruction + "\n" + constraint;
    }

    /**
     * 构建结构化 User Prompt。
     *
     * @param state 当前 Agent 状态
     * @return User Prompt 文本
     */
    String buildUserPrompt(AgentState state) {
        var sb = new StringBuilder();

        // 用户原始消息
        sb.append("用户请求: ").append(state.goal()).append("\n");

        // 预算剩余信息
        sb.append("预算剩余: Token=").append(state.budget().tokensRemaining())
                .append(", 已用步骤=").append(state.stepCount()).append("\n");

        // 已执行步骤摘要
        if (!state.steps().isEmpty()) {
            sb.append("已执行步骤:\n");
            for (int i = 0; i < state.steps().size(); i++) {
                StepRecord step = state.steps().get(i);
                sb.append("  ").append(i + 1).append(". ")
                        .append(step.toolId() != null ? step.toolId() : "系统")
                        .append(" - ").append(step.success() ? "成功" : "失败")
                        .append(": ").append(truncate(step.output(), 200))
                        .append("\n");
            }
        }

        // 当前计划信息
        if (state.plan() != null) {
            sb.append("当前计划: ").append(state.plan().rationale()).append("\n");
            sb.append("计划进度: ").append(state.planStepIndex())
                    .append("/").append(state.plan().steps().size()).append("\n");
        }

        return sb.toString();
    }

    /** 截断文本到指定长度。 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
