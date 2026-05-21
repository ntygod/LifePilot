package com.lifepilot.agent.learning.experience;

import com.lifepilot.agent.model.ReactAgentState;

/**
 * 执行上下文枚举 — 标识经验的产生和适用环境。
 *
 * @author zsg
 * @since 2026-03-18
 */
public enum ExecutionContext {
    /** 主 Agent 用户交互。 */
    MAIN_AGENT,
    /** 子 Agent 委托执行。 */
    SUB_AGENT,
    /** 评估框架执行。 */
    EVAL,
    /** 工作流 LLM 步骤。 */
    WORKFLOW_LLM,
    /** 定时任务执行。 */
    SCHEDULED_TASK;

    /**
     * 从 ReactAgentState 推断执行上下文。
     *
     * @param state Agent 状态
     * @return 推断的执行上下文
     */
    public static ExecutionContext infer(ReactAgentState state) {
        if (state.sessionId() != null && state.sessionId().startsWith("eval-")) {
            return EVAL;
        }
        if (state.depth() > 0) {
            return SUB_AGENT;
        }
        // WORKFLOW_LLM 和 SCHEDULED_TASK 由调用方显式传入
        return MAIN_AGENT;
    }
}
