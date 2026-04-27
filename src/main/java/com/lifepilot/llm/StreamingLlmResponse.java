package com.lifepilot.llm;

import com.lifepilot.llm.stream.LlmStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 流式 LLM 响应包装 — 携带 {@link Flux} of {@link LlmStreamEvent} + provider/model 元信息。
 *
 * <p>替代原 {@code Flux<String>} 设计，承载推理模型多轮契约所需的全部维度
 * （reasoning / content / tool_calls / usage / done / error）。消费方按
 * sealed pattern matching 分派事件。
 *
 * @param events     LlmStreamEvent 流
 * @param providerId Provider ID（配置 id）
 * @param modelId    模型 ID（配置 modelName）
 * @author zsg
 * @since 2026-04-27
 */
public record StreamingLlmResponse(
        Flux<LlmStreamEvent> events,
        String providerId,
        String modelId
) {
}
