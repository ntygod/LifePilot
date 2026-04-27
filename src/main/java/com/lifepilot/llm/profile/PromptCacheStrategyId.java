package com.lifepilot.llm.profile;

/**
 * Prompt 缓存策略标识 — 与 com.lifepilot.llm.cache.PromptCacheStrategy 实现对应。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum PromptCacheStrategyId {
    ANTHROPIC_EPHEMERAL,
    DASHSCOPE_EXPLICIT,
    OPENAI_AUTO,
    NOOP
}
