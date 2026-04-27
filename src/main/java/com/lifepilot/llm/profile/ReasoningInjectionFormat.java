package com.lifepilot.llm.profile;

/**
 * 多轮 history 中 reasoning 内容的注入格式。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ReasoningInjectionFormat {
    /** assistant message 平级加 reasoning_content 字段（DeepSeek / Qwen） */
    CONTENT_ONLY,
    /** 通过 extra_body 等私有字段注入（保留位） */
    EXTRA_BODY,
    /** content array 第一块为 thinking block（Anthropic） */
    THINKING_BLOCK
}
