package com.lifepilot.llm.profile;

/**
 * 多轮 history 注入规则 — 由 ChatHistoryAssembler 按规则序列化 history。
 *
 * @param injectReasoning           是否回传 reasoning_content
 * @param injectReasoningSignature  是否回传 reasoning_signature（仅 Anthropic）
 * @param injectToolCalls           是否回传 tool_calls 元数据
 * @param format                    reasoning 注入格式
 * @author zsg
 * @since 2026-04-27
 */
public record MultiTurnHistoryRules(
        boolean injectReasoning,
        boolean injectReasoningSignature,
        boolean injectToolCalls,
        ReasoningInjectionFormat format
) {
    /** 不回传任何 reasoning，标准多轮（OpenAI 官方 / 非推理模型） */
    public static MultiTurnHistoryRules standard() {
        return new MultiTurnHistoryRules(false, false, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** DeepSeek / Qwen / 火山等：回传 reasoning_content，content 平级注入 */
    public static MultiTurnHistoryRules contentOnlyReasoning() {
        return new MultiTurnHistoryRules(true, false, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** Anthropic：回传 thinking block + signature */
    public static MultiTurnHistoryRules anthropicThinkingBlock() {
        return new MultiTurnHistoryRules(true, true, true, ReasoningInjectionFormat.THINKING_BLOCK);
    }
}
