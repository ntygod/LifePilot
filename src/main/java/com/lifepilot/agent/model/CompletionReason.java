package com.lifepilot.agent.model;

/**
 * ReAct 循环完成原因。
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum CompletionReason {
    DIRECT_ANSWER,
    EXPLICIT_COMPLETED,
    EXPLICIT_BLOCKED,
    BUDGET_EXCEEDED,
    ITERATION_LIMIT,
    LLM_FAILURE_LIMIT,
    EMPTY_RESPONSE_LIMIT,
    EARLY_STOP_REJECTED,
    SUSPENDED,
    UNEXPECTED_EXCEPTION,
    CANCELLED
}
