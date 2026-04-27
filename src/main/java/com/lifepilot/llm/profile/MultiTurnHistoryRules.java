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
     *
     * <p><b>当前未启用</b> — 启用前提是 assistant 持久化路径必须把 LlmResponse.toolCalls 写入
     * {@code session_transcript_entries.payload_json}，否则 ChatHistoryAssembler 永远读不到
     * tool_calls，会错走「无 tool_call 不注入」分支，第 3 轮 ReAct 调用 DeepSeek 直接 400
     * （reasoning_content must be passed back）。
     *
     * <p>真实 wiring 待办详见 {@code memory/project_reasoning_content_wiring_gap.md}：
     * 需要 Phase 4 流式 chunkToEvents / Phase 10 同步 toLlmResponse 暴露 reasoning_content
     * + AgentPersistenceHandler 改用 {@code ChatTurnService.buildAssistantPayload}。
     * 完成后再把 DEEPSEEK_OFFICIAL / VOLCENGINE_ARK profile 切回此规则。
     */
    public static MultiTurnHistoryRules deepseekContentOnlyReasoning() {
        return new MultiTurnHistoryRules(true, false, true, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** Anthropic：回传 thinking block + signature */
    public static MultiTurnHistoryRules anthropicThinkingBlock() {
        return new MultiTurnHistoryRules(true, true, true, false, ReasoningInjectionFormat.THINKING_BLOCK);
    }
}
