package com.lifepilot.llm.stream;

/**
 * 错误事件。
 *
 * <p>流式过程中发生错误时由 Adapter 发出，消费方据此中止处理并向上游传播异常。
 *
 * @param code    错误码（Provider 私有错误码或通用归类）
 * @param message 错误描述
 * @author zsg
 * @since 2026-04-27
 */
public record ErrorEvent(String code, String message) implements LlmStreamEvent {
}
