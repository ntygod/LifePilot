package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

/**
 * Token 用量更新事件。
 *
 * <p><b>契约</b>：本事件携带的是当次 LLM 调用的<b>累计</b> token 用量。
 * 流式过程中可能多次发出 UsageEvent（每次 chunk 携带 usage 时），
 * 后续事件代表累计值的更新（即覆盖前一个 UsageEvent，不要叠加）。
 *
 * <p>Adapter 端的 chunkToEvents 实现负责把厂商的"增量"协议（部分 SDK 仅最后 chunk 发出 usage）
 * 与"累计"协议（每个 chunk 发出累计值）统一为本累计语义。Spring AI 1.x 的 Usage 已是累计值，
 * 默认 chunkToEvents 直接透传。
 *
 * @param inputTokens       累计输入 token 数
 * @param outputTokens      累计输出 token 数
 * @param reasoningTokens   累计 reasoning token 数（推理模型，可空表示该 Provider 未上报）
 * @param cachedInputTokens 累计缓存命中 token 数
 * @author zsg
 * @since 2026-04-27
 */
public record UsageEvent(int inputTokens,
                         int outputTokens,
                         @Nullable Integer reasoningTokens,
                         int cachedInputTokens) implements LlmStreamEvent {
}
