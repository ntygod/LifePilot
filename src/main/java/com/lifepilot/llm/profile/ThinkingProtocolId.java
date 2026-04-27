package com.lifepilot.llm.profile;

/**
 * 推理模式协议标识 — 用于路由到具体 ThinkingProtocol 实现。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ThinkingProtocolId {
    /** DeepSeek V4 系列：extra_body.thinking + reasoning_content 多轮回传 */
    DEEPSEEK,
    /** Qwen3 系列：chat_template_kwargs.enable_thinking + reasoning_content */
    QWEN,
    /** OpenAI o-系列 / GPT-5：reasoning.effort 参数（响应不返回 reasoning） */
    OPENAI_REASONING_EFFORT,
    /** Anthropic Claude 4.x：thinking block + signature 验证回传 */
    ANTHROPIC,
    /** 非推理模型 / 关闭模式 */
    NONE
}
