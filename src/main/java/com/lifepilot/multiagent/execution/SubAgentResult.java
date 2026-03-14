package com.lifepilot.multiagent.execution;

/**
 * 子 Agent 执行结果。
 *
 * <p>封装子 Agent 委托执行的返回信息，包括 traceId、目标 agentId、
 * 成功/失败标志、输出内容和 Token 消耗。</p>
 *
 * @param traceId    子 Agent 执行的 traceId
 * @param agentId    目标 Agent ID
 * @param success    是否执行成功
 * @param output     输出内容（成功时为 Agent 响应，失败时为错误信息）
 * @param tokensUsed Token 消耗量
 * @author zsg
 * @since 2026-03-15
 */
public record SubAgentResult(
        String traceId,
        String agentId,
        boolean success,
        String output,
        int tokensUsed
) {}
