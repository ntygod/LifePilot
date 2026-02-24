package com.lifepilot.agent.model;

/**
 * Agent 错误类型枚举。
 *
 * @author zsg
 * @since 2026-07-20
 */
public enum AgentErrorType {

    /** LLM 服务不可用 */
    LLM_UNAVAILABLE,

    /** LLM 输出解析失败 */
    LLM_PARSE_FAILURE,

    /** 工具执行失败 */
    TOOL_EXECUTION_FAILURE,

    /** 工具执行超时 */
    TOOL_TIMEOUT,

    /** 护栏违规 */
    GUARDRAIL_VIOLATION,

    /** 内部错误 */
    INTERNAL_ERROR
}
