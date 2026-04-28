package com.lifepilot.llm.stream;

/**
 * LLM 流式事件 — sealed interface，6 种具体事件。
 *
 * <p>替代原 {@code Flux<String>} 的字符串流，承载推理模型多轮契约所需的全部维度：
 * <ul>
 *   <li>{@link ReasoningChunk} — 推理过程片段（DeepSeek V4 / Qwen3 / Anthropic thinking block）</li>
 *   <li>{@link ContentChunk} — 正文片段</li>
 *   <li>{@link ToolCallDelta} — 工具调用增量</li>
 *   <li>{@link UsageEvent} — token 用量统计</li>
 *   <li>{@link DoneEvent} — 流结束</li>
 *   <li>{@link ErrorEvent} — 错误信号</li>
 * </ul>
 *
 * <p>消费方按 sealed pattern matching 分派事件，编译器静态校验无遗漏分支。
 *
 * @author zsg
 * @since 2026-04-27
 */
public sealed interface LlmStreamEvent
        permits ReasoningChunk, ContentChunk, ToolCallDelta, UsageEvent, DoneEvent, ErrorEvent {
}
