package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

/**
 * Token 用量事件。
 *
 * <p>承载本次调用的 token 计费维度，含 reasoning_tokens（OpenAI 等返回）
 * 和 cached_input_tokens（prompt cache 命中）。部分 Provider 仅在最后一个 chunk
 * 发出该事件，部分按 chunk 增量推送。
 *
 * @param inputTokens       输入 token 数
 * @param outputTokens      输出 token 数
 * @param reasoningTokens   推理 token 数（OpenAI 等返回，可空）
 * @param cachedInputTokens prompt cache 命中的 token 数
 * @author zsg
 * @since 2026-04-27
 */
public record UsageEvent(int inputTokens,
                         int outputTokens,
                         @Nullable Integer reasoningTokens,
                         int cachedInputTokens) implements LlmStreamEvent {
}
