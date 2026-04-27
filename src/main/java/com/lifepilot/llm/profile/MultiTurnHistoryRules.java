package com.lifepilot.llm.profile;

/**
 * 多轮 history 注入规则 — 由 ChatHistoryAssembler 按规则序列化 history。
 *
 * @param injectReasoning                   是否回传 reasoning_content
 * @param injectReasoningSignature          是否回传 reasoning_signature（仅 Anthropic）
 * @param injectToolCalls                   是否回传 tool_calls 元数据
 * @param injectReasoningOnlyWithToolCalls  仅当 assistant 消息含 tool_calls 时才注入 reasoning（DeepSeek 官方契约）
 * @param format                            reasoning 注入格式
 * @author zsg
 * @since 2026-04-27
 */
public record MultiTurnHistoryRules(
        boolean injectReasoning,
        boolean injectReasoningSignature,
        boolean injectToolCalls,
        boolean injectReasoningOnlyWithToolCalls,
        ReasoningInjectionFormat format
) {
    /** 不回传任何 reasoning，标准多轮（OpenAI 官方 / 非推理模型） */
    public static MultiTurnHistoryRules standard() {
        return new MultiTurnHistoryRules(false, false, true, false, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** 始终注入 reasoning_content（Qwen 等保守策略：文档未明确，避免后续版本变契约 400） */
    public static MultiTurnHistoryRules contentOnlyReasoning() {
        return new MultiTurnHistoryRules(true, false, true, false, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /**
     * 仅 tool_call 场景注入 reasoning_content（DeepSeek 官方契约）。
     *
     * <p>按 DeepSeek 官方文档：仅当 assistant 消息含 tool_calls 时才需要回传 reasoning_content；
     * 无 tool_call 场景下 API 会忽略该字段，回传是浪费 prompt token。
     */
    public static MultiTurnHistoryRules deepseekContentOnlyReasoning() {
        return new MultiTurnHistoryRules(true, false, true, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** Anthropic：回传 thinking block + signature */
    public static MultiTurnHistoryRules anthropicThinkingBlock() {
        return new MultiTurnHistoryRules(true, true, true, false, ReasoningInjectionFormat.THINKING_BLOCK);
    }
}
