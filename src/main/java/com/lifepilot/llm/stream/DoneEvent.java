package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

/**
 * 流结束事件。
 *
 * <p>消费方收到此事件即认为流式响应完成，不再期待更多事件。
 * Phase 4 简化版：OpenAi/Anthropic/Ollama Adapter 暂不主动发出 DoneEvent，
 * 由消费方自行判定 Flux 完成；Phase 10 集成测试时由 Adapter 显式发出。
 *
 * @param finishReason 完成原因（stop / tool_calls / length / interrupted / ...，可空）
 * @author zsg
 * @since 2026-04-27
 */
public record DoneEvent(@Nullable String finishReason) implements LlmStreamEvent {
}
