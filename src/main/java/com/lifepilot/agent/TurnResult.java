package com.lifepilot.agent;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * ReactAgentLoop 单轮调用结果。
 *
 * <p>作为 {@link ReactAgentLoop#run(String, org.springframework.ai.chat.messages.UserMessage)}
 * 的返回值，封装一次 Agent 循环执行过程中的关键产物：
 * <ul>
 *   <li>{@code sessionId} / {@code turnId} — 会话与轮次标识</li>
 *   <li>{@code toolInvocations} — 按调用顺序排列的工具调用序列（ToolCall + Observation 配对）</li>
 *   <li>{@code finalText} — LLM 生成的最终回复（正常终止时非空，预算耗尽/挂起时可能为空）</li>
 *   <li>{@code completed} — 是否正常结束（{@code true}），{@code false} 表示预算耗尽、挂起或其他中断</li>
 * </ul>
 *
 * <p>与 HTTP/SSE 入口返回的 {@code AgentResponse} 不同，TurnResult 面向测试与 SDK 调用方，
 * 仅保留足以复现 Agent 行为所需的最小信息，不携带 Token 统计、Trace 上下文等观察性字段。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public record TurnResult(
        String sessionId,
        @Nullable String turnId,
        List<ToolInvocation> toolInvocations,
        @Nullable String finalText,
        boolean completed
) {

    public TurnResult {
        toolInvocations = toolInvocations != null ? List.copyOf(toolInvocations) : List.of();
    }

    /**
     * 工具调用快照 — 与 ReAct 步骤中的 ToolCall + Observation 配对一一对应。
     *
     * @param tool       工具技术标识（如 {@code file.read}）
     * @param argsJson   工具入参 JSON（原样来自 LLM tool call）
     * @param resultJson 工具结果 JSON（来自 Observation.output；失败时可能是错误描述）
     */
    public record ToolInvocation(String tool, String argsJson, String resultJson) {}
}
