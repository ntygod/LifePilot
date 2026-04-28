package com.lifepilot.llm.stream;

/**
 * 正文片段事件。
 *
 * <p>承载 assistant message 的文本增量。
 *
 * @param delta 正文文本增量
 * @author zsg
 * @since 2026-04-27
 */
public record ContentChunk(String delta) implements LlmStreamEvent {
}
