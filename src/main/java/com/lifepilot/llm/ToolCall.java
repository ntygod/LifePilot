package com.lifepilot.llm;

/**
 * LLM tool call 元数据。
 *
 * <p>用于在 {@link LlmResponse} / {@code LlmStreamEvent} 中承载 provider 返回的工具调用信息。
 *
 * @param id            tool call ID
 * @param name          工具名
 * @param argumentsJson JSON 字符串形式的参数
 * @author zsg
 * @since 2026-04-27
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
