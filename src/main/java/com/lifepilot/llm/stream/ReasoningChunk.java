package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

/**
 * 推理过程片段事件。
 *
 * <p>承载推理模型的 reasoning_content 增量片段。Anthropic thinking block 还附带 signature。
 *
 * @param delta     推理文本增量
 * @param signature 推理签名（仅 Anthropic 使用，可空）
 * @author zsg
 * @since 2026-04-27
 */
public record ReasoningChunk(String delta, @Nullable String signature) implements LlmStreamEvent {
}
