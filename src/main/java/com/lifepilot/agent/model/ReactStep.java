package com.lifepilot.agent.model;

/**
 * ReAct 循环步骤类型 — sealed interface。
 *
 * <p>替代原 {@code Action} 的 9 种类型，简化为 4 种步骤类型：
 * <ul>
 *   <li>{@link Thought} — LLM 的推理思考</li>
 *   <li>{@link ToolCall} — 工具调用记录（由 Spring AI function calling 触发）</li>
 *   <li>{@link Observation} — 工具调用结果观察</li>
 *   <li>{@link Answer} — 最终回答（LLM 未调用工具时的纯文本输出）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-14
 */
public sealed interface ReactStep permits
        ReactStep.Thought,
        ReactStep.ToolCall,
        ReactStep.Observation,
        ReactStep.Answer {

    /** LLM 的推理思考。 */
    record Thought(String content) implements ReactStep {}

    /**
     * 工具调用记录（由 Spring AI function calling 触发）。
     *
     * @param toolId    工具标识
     * @param inputJson 工具输入 JSON
     * @param latencyMs 调用耗时（毫秒）
     */
    record ToolCall(
            String toolId,
            String inputJson,
            long latencyMs
    ) implements ReactStep {}

    /**
     * 工具调用结果观察。
     *
     * @param toolId     工具标识
     * @param success    是否成功
     * @param output     工具输出内容
     * @param tokensUsed 本次调用消耗的 Token 数
     */
    record Observation(
            String toolId,
            boolean success,
            String output,
            int tokensUsed
    ) implements ReactStep {}

    /** 最终回答（LLM 未调用工具时的纯文本输出）。 */
    record Answer(String content) implements ReactStep {}
}
